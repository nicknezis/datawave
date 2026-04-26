package datawave.query.cypher.executor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import datawave.edge.util.EdgeKey;
import datawave.edge.util.EdgeValue;
import datawave.microservice.query.Query;
import datawave.query.cypher.mapping.EdgeAttributeSlot;
import datawave.query.cypher.mapping.RelMapping;
import datawave.query.cypher.physical.HopTranslation;
import datawave.query.cypher.physical.HopTranslator;
import datawave.query.cypher.planner.CypherPlan;
import datawave.query.cypher.planner.HopSpec;
import datawave.query.cypher.planner.NodeBinding;
import datawave.query.cypher.planner.Projection;
import datawave.query.cypher.planner.SortSpec;
import datawave.query.iterator.filter.EdgeFilterIterator;
import datawave.query.util.QueryScannerHelper;

/**
 * Executes a multi-hop {@link CypherPlan} against the edge table.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Hop 0: scan with the plan's literal identity filters; collect
 *       {@link PathTuple}s.</li>
 *   <li>Hop N (N &gt; 0): derive the frontier (unique source-node values from
 *       the previous hop's sinks), re-translate the hop with
 *       {@link HopTranslator#translate(HopSpec, Set)}, scan, and join
 *       on the shared junction variable via an in-memory hash index.</li>
 *   <li>Post-process: optionally run shard enrichment, then apply
 *       DISTINCT, ORDER BY, SKIP (in that order). The caller applies LIMIT.</li>
 * </ol>
 *
 * <p>All intermediate results are held in memory (multi-hop join is inherently
 * in-memory). LIMIT from the plan is enforced by the caller via
 * {@code Iterators.limit()} on the resulting iterator.
 */
public final class MultiHopExecutor {

    private static final Logger log = LoggerFactory.getLogger(MultiHopExecutor.class);

    private static final int FILTER_PRIORITY = 130;

    private final AccumuloClient client;
    private final Set<Authorizations> auths;
    private final String tableName;
    private final int queryThreads;
    private final Query query;
    private final HopTranslator hopTranslator;
    private final ShardEnrichmentService enrichmentService;

    public MultiHopExecutor(AccumuloClient client, Set<Authorizations> auths, String tableName, int queryThreads, Query query,
                    HopTranslator hopTranslator, ShardEnrichmentService enrichmentService) {
        this.client = client;
        this.auths = auths;
        this.tableName = tableName;
        this.queryThreads = queryThreads;
        this.query = query;
        this.hopTranslator = hopTranslator;
        this.enrichmentService = enrichmentService;
    }

    /**
     * Executes all hops and returns the fully post-processed result list
     * (DISTINCT + ORDER BY + SKIP applied; LIMIT is applied by the caller).
     *
     * @param plan the logical plan
     * @param initialTranslations one pre-built {@link HopTranslation} per hop
     *        (built from the plan's literal filters; subsequent hops are
     *        re-translated with frontier values at runtime)
     */
    public List<PathTuple> execute(CypherPlan plan, List<HopTranslation> initialTranslations) throws Exception {
        List<HopSpec> hops = plan.getHops();
        List<PathTuple> current = new ArrayList<>();

        for (int i = 0; i < hops.size(); i++) {
            HopSpec hop = hops.get(i);
            HopTranslation translation;

            if (i == 0) {
                translation = initialTranslations.get(0);
            } else {
                // Build frontier from previous results.
                String junctionVar = hop.getSource().getVariable();
                Set<String> frontier = buildFrontier(current, junctionVar);
                // Intersect with any literal identity filters on the hop source
                // (e.g., from WITH WHERE or inline node properties on the junction
                // variable), so that paths through disallowed junction values are
                // not expanded.
                Map<String,String> srcIdentityEquals = hop.getSource().getIdentityEquals();
                if (!srcIdentityEquals.isEmpty()) {
                    frontier.retainAll(new LinkedHashSet<>(srcIdentityEquals.values()));
                }
                if (frontier.isEmpty()) {
                    return new ArrayList<>();
                }
                translation = hopTranslator.translate(hop, frontier);
            }

            List<Map.Entry<Key,Value>> edgeRows = scanEdges(translation);

            if (i == 0) {
                for (Map.Entry<Key,Value> row : edgeRows) {
                    PathTuple t = buildTupleFromEdge(hop, translation, row);
                    if (t != null) {
                        current.add(t);
                    }
                }
            } else {
                String junctionVar = hop.getSource().getVariable();
                Map<String,List<PathTuple>> index = buildIndex(current, junctionVar);
                List<PathTuple> expanded = new ArrayList<>();
                for (Map.Entry<Key,Value> row : edgeRows) {
                    PathTuple edgeTuple = buildTupleFromEdge(hop, translation, row);
                    if (edgeTuple == null) {
                        continue;
                    }
                    String junctionValue = edgeTuple.get(junctionVar);
                    List<PathTuple> matching = index.get(junctionValue);
                    if (matching != null) {
                        for (PathTuple prev : matching) {
                            expanded.add(prev.merge(edgeTuple));
                        }
                    }
                }
                current = expanded;
            }
        }

        // Shard enrichment for NODE_SHARD_PROPERTY projections.
        boolean needsEnrichment = false;
        for (Projection p : plan.getProjections()) {
            if (p.getKind() == Projection.Kind.NODE_SHARD_PROPERTY) {
                needsEnrichment = true;
                break;
            }
        }
        if (needsEnrichment) {
            current = enrich(current, plan);
        }

        // DISTINCT — deduplicate on projected column values.
        if (plan.isDistinct()) {
            current = distinct(current, plan);
        }

        // ORDER BY.
        if (!plan.getOrderBy().isEmpty()) {
            sort(current, plan.getOrderBy(), plan.getProjections());
        }

        // SKIP.
        if (plan.getSkipBoxed().isPresent()) {
            int skip = (int) Math.min(plan.getSkipBoxed().get(), current.size());
            current = new ArrayList<>(current.subList(skip, current.size()));
        }

        return current;
    }

    // ---- scan -----------------------------------------------------------

    private List<Map.Entry<Key,Value>> scanEdges(HopTranslation translation) throws Exception {
        BatchScanner scanner = QueryScannerHelper.createBatchScanner(client, tableName, auths, queryThreads, query);
        scanner.setRanges(translation.getRanges());

        IteratorSetting filter = new IteratorSetting(FILTER_PRIORITY,
                        EdgeFilterIterator.class.getSimpleName() + "_" + FILTER_PRIORITY, EdgeFilterIterator.class);
        filter.addOption(EdgeFilterIterator.JEXL_OPTION, translation.getFilterJexl());
        filter.addOption(EdgeFilterIterator.PROTOBUF_OPTION, "TRUE");
        filter.addOption(EdgeFilterIterator.INCLUDE_STATS_OPTION, "FALSE");
        scanner.addScanIterator(filter);

        List<Map.Entry<Key,Value>> out = new ArrayList<>();
        try {
            for (Map.Entry<Key,Value> entry : scanner) {
                out.add(entry);
            }
        } finally {
            scanner.close();
        }
        return out;
    }

    // ---- tuple construction from an edge row ----------------------------

    private PathTuple buildTupleFromEdge(HopSpec hop, HopTranslation translation, Map.Entry<Key,Value> row) {
        EdgeKey edgeKey;
        try {
            edgeKey = EdgeKey.decode(row.getKey());
        } catch (Exception e) {
            log.debug("EdgeKey decode failed, skipping row: {}", e.getMessage());
            return null;
        }

        String diskSource = edgeKey.getSourceData();
        String diskSink = edgeKey.getSinkData();

        // Prefer un-normalized values from the protobuf payload when available.
        try {
            EdgeValue ev = EdgeValue.decode(row.getValue());
            if (ev.getSourceValue() != null) {
                diskSource = ev.getSourceValue();
            }
            if (ev.getSinkValue() != null) {
                diskSink = ev.getSinkValue();
            }
        } catch (Exception e) {
            // Fall back to row-key endpoints — not an error.
        }

        NodeBinding sourceBinding = hop.getSource();
        NodeBinding sinkBinding = hop.getSink();
        String sourceVarValue;
        String sinkVarValue;

        if (translation.isSwappedEndpoints()) {
            sourceVarValue = diskSink;
            sinkVarValue = diskSource;
        } else {
            sourceVarValue = diskSource;
            sinkVarValue = diskSink;
        }

        Map<String,String> tupleMap = new LinkedHashMap<>();
        tupleMap.put(sourceBinding.getVariable(), sourceVarValue);
        tupleMap.put(sinkBinding.getVariable(), sinkVarValue);

        // Populate relationship attribute values if the hop has a relVariable.
        hop.getRelVariable().ifPresent(relVar -> {
            RelMapping rel = hop.getRel();
            for (Map.Entry<String,EdgeAttributeSlot> attrEntry : rel.getAttributeMappings().entrySet()) {
                String cypherProp = attrEntry.getKey();
                EdgeAttributeSlot slot = attrEntry.getValue();
                String attrValue;
                switch (slot) {
                    case ATTRIBUTE1:
                        attrValue = edgeKey.getAttribute1();
                        break;
                    case ATTRIBUTE2:
                        attrValue = edgeKey.getAttribute2();
                        break;
                    case ATTRIBUTE3:
                        attrValue = edgeKey.getAttribute3();
                        break;
                    default:
                        attrValue = null;
                }
                if (attrValue != null) {
                    tupleMap.put(relVar + "." + cypherProp, attrValue);
                }
            }
        });

        return PathTuple.of(tupleMap);
    }

    // ---- frontier + join ------------------------------------------------

    private Set<String> buildFrontier(List<PathTuple> tuples, String variable) {
        Set<String> frontier = new LinkedHashSet<>();
        for (PathTuple t : tuples) {
            String v = t.get(variable);
            if (v != null) {
                frontier.add(v);
            }
        }
        return frontier;
    }

    private Map<String,List<PathTuple>> buildIndex(List<PathTuple> tuples, String keyVar) {
        Map<String,List<PathTuple>> index = new LinkedHashMap<>();
        for (PathTuple t : tuples) {
            String key = t.get(keyVar);
            if (key != null) {
                index.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
            }
        }
        return index;
    }

    // ---- post-processing ------------------------------------------------

    private List<PathTuple> enrich(List<PathTuple> tuples, CypherPlan plan) {
        if (enrichmentService == null) {
            throw new IllegalStateException("ShardEnrichmentService is required for non-identity node property projections but was not configured");
        }
        // Build the set of (nodeVar, propertyName) pairs that need enrichment.
        Map<String,Set<String>> nodePropsNeeded = new LinkedHashMap<>();
        for (Projection p : plan.getProjections()) {
            if (p.getKind() == Projection.Kind.NODE_SHARD_PROPERTY) {
                nodePropsNeeded.computeIfAbsent(p.getVariable(), k -> new LinkedHashSet<>()).add(p.getProperty());
            }
        }
        // Also gather shard filter properties needed to post-filter tuples.
        for (HopSpec hop : plan.getHops()) {
            addShardFilterProperties(hop.getSource(), nodePropsNeeded);
            addShardFilterProperties(hop.getSink(), nodePropsNeeded);
        }
        return enrichmentService.enrich(tuples, nodePropsNeeded, plan);
    }

    private void addShardFilterProperties(NodeBinding binding, Map<String,Set<String>> nodePropsNeeded) {
        if (binding.requiresShardEnrichment()) {
            nodePropsNeeded.computeIfAbsent(binding.getVariable(), k -> new LinkedHashSet<>())
                            .addAll(binding.getShardPropertyFilters().keySet());
        }
    }

    private List<PathTuple> distinct(List<PathTuple> tuples, CypherPlan plan) {
        List<Projection> projections = plan.getProjections();
        Set<String> seen = new LinkedHashSet<>();
        List<PathTuple> out = new ArrayList<>();
        for (PathTuple t : tuples) {
            String key = projectedKey(t, projections);
            if (seen.add(key)) {
                out.add(t);
            }
        }
        return out;
    }

    private String projectedKey(PathTuple t, List<Projection> projections) {
        StringBuilder sb = new StringBuilder();
        for (Projection p : projections) {
            if (sb.length() > 0) {
                sb.append('');
            }
            String v = resolveProjectedValue(t, p);
            if (v != null) {
                sb.append(v);
            }
        }
        return sb.toString();
    }

    private String resolveProjectedValue(PathTuple t, Projection p) {
        if (p.getKind() == Projection.Kind.NODE_PROPERTY) {
            return t.get(p.getVariable());
        }
        if (p.getKind() == Projection.Kind.REL_PROPERTY) {
            return t.get(p.getVariable() + "." + p.getProperty());
        }
        // NODE_SHARD_PROPERTY — enriched value stored as "var.prop"
        return t.get(p.getVariable() + "." + p.getProperty());
    }

    private void sort(List<PathTuple> tuples, List<SortSpec> orderBy, List<Projection> projections) {
        // Build alias → Projection lookup for resolving sort keys.
        Map<String,Projection> byAlias = new LinkedHashMap<>();
        for (Projection p : projections) {
            byAlias.put(p.getAlias(), p);
        }

        Comparator<PathTuple> comparator = null;
        for (SortSpec spec : orderBy) {
            Projection proj = byAlias.get(spec.getAlias());
            if (proj == null) {
                continue; // semantic analyzer guarantees this is a projected alias
            }
            final Projection finalProj = proj;
            Comparator<PathTuple> c = Comparator.comparing(t -> {
                String val = resolveProjectedValue(t, finalProj);
                return val != null ? val : "";
            });
            if (spec.getDirection() == datawave.query.cypher.ast.SortItem.Direction.DESC) {
                c = c.reversed();
            }
            comparator = (comparator == null) ? c : comparator.thenComparing(c);
        }
        if (comparator != null) {
            tuples.sort(comparator);
        }
    }
}
