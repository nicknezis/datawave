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
import datawave.query.cypher.planner.CypherUnsupportedException;
import datawave.query.cypher.planner.GroupingSpec;
import datawave.query.cypher.planner.HopSpec;
import datawave.query.cypher.planner.NodeBinding;
import datawave.query.cypher.planner.Projection;
import datawave.query.cypher.planner.SortSpec;
import datawave.query.iterator.filter.EdgeFilterIterator;
import datawave.query.util.QueryScannerHelper;

/**
 * Executes a multi-hop {@link CypherPlan} against the edge table.
 *
 * <p>M3 algorithm:
 * <ol>
 *   <li>Hop 0: scan with the plan's literal identity filters (or, when hop 0
 *       is variable-length, delegate to {@link VariableLengthExpander} to
 *       drive the BFS from the literal frontier).</li>
 *   <li>Hop N (N &gt; 0): derive the frontier (unique source-node values
 *       from the previous hop's sinks), and either translate-and-scan once
 *       (fixed length) or delegate to {@link VariableLengthExpander} (when
 *       {@link HopSpec#isVariableLength()}).</li>
 *   <li>Post-process: optionally run shard enrichment, fold through
 *       {@link StreamingAggregator} if the plan carries a
 *       {@link GroupingSpec}, then apply DISTINCT, ORDER BY, SKIP
 *       (in that order). The caller applies LIMIT.</li>
 * </ol>
 *
 * <p>Every contributing edge cell's {@link org.apache.accumulo.core.security.ColumnVisibility}
 * is carried on the {@link PathTuple} so the transformer can compose composite
 * visibilities via {@link datawave.marking.MarkingFunctions#combine}.
 *
 * <p>Executor remains one-shot in M3 — checkpointing across BFS boundaries is
 * deferred. The moment this becomes streaming, the BFS frontier + per-path
 * edge-trail history must be added to a serialisable checkpoint state.
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
    private final ExecutorLimits limits;

    public MultiHopExecutor(AccumuloClient client, Set<Authorizations> auths, String tableName, int queryThreads, Query query,
                    HopTranslator hopTranslator, ShardEnrichmentService enrichmentService) {
        this(client, auths, tableName, queryThreads, query, hopTranslator, enrichmentService, ExecutorLimits.DEFAULTS);
    }

    public MultiHopExecutor(AccumuloClient client, Set<Authorizations> auths, String tableName, int queryThreads, Query query,
                    HopTranslator hopTranslator, ShardEnrichmentService enrichmentService, ExecutorLimits limits) {
        this.client = client;
        this.auths = auths;
        this.tableName = tableName;
        this.queryThreads = queryThreads;
        this.query = query;
        this.hopTranslator = hopTranslator;
        this.enrichmentService = enrichmentService;
        this.limits = limits == null ? ExecutorLimits.DEFAULTS : limits;
    }

    /**
     * Executes all hops and returns the fully post-processed result list
     * (aggregation + DISTINCT + ORDER BY + SKIP applied; LIMIT is applied by
     * the caller).
     *
     * @param plan the logical plan
     * @param initialTranslations the pre-built {@link HopTranslation} for the
     *        first hop when it is fixed-length; may be {@code null} or empty
     *        when hop 0 is variable-length (translations are built per BFS
     *        step from the literal frontier instead)
     */
    public List<PathTuple> execute(CypherPlan plan, List<HopTranslation> initialTranslations) throws Exception {
        List<HopSpec> hops = plan.getHops();
        List<PathTuple> current = new ArrayList<>();
        VariableLengthExpander expander = new VariableLengthExpander(hopTranslator, this::scanEdges, this::buildTupleFromEdge, limits);

        for (int i = 0; i < hops.size(); i++) {
            HopSpec hop = hops.get(i);

            if (i == 0) {
                current = executeFirstHop(hop, initialTranslations, expander);
            } else {
                if (current.isEmpty()) {
                    return new ArrayList<>();
                }
                current = executeSubsequentHop(hop, current, expander, i);
            }

            if (current.isEmpty()) {
                return new ArrayList<>();
            }
        }

        // Shard enrichment for NODE_SHARD_PROPERTY projections.
        if (needsEnrichment(plan)) {
            current = enrich(current, plan);
        }

        // Streaming aggregation (M3).
        if (plan.getGroupingSpec().isPresent()) {
            StreamingAggregator aggregator = new StreamingAggregator(plan.getGroupingSpec().get(), limits.getMaxAggregateGroups());
            current = aggregator.aggregate(current);
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

    private List<PathTuple> executeFirstHop(HopSpec hop, List<HopTranslation> initialTranslations, VariableLengthExpander expander) throws Exception {
        if (hop.isVariableLength()) {
            // Build the literal source-frontier from the plan's identity equals and
            // drive BFS from there. The literal-frontier "incoming" is one tuple per
            // source identity value, binding the hop's source variable.
            List<PathTuple> seed = buildLiteralFrontierTuples(hop);
            if (seed.isEmpty()) {
                throw new CypherUnsupportedException("variable-length hop 0 requires a literal identity filter on its source endpoint");
            }
            return expander.expand(hop, seed, hop.getSource().getVariable(), true);
        }
        if (initialTranslations == null || initialTranslations.isEmpty()) {
            throw new IllegalArgumentException("initialTranslations must contain the first hop translation when the plan's first hop is fixed-length");
        }
        HopTranslation translation = initialTranslations.get(0);
        List<EdgeRow> edgeRows = scanEdges(translation);
        List<PathTuple> out = new ArrayList<>(edgeRows.size());
        for (EdgeRow row : edgeRows) {
            PathTuple t = buildTupleFromEdge(hop, translation, row);
            if (t != null) {
                if (hop.getPathVariable().isPresent()) {
                    t = decoratePathForFixedHop(hop, translation, t);
                }
                out.add(t);
            }
        }
        return out;
    }

    private List<PathTuple> executeSubsequentHop(HopSpec hop, List<PathTuple> previous, VariableLengthExpander expander, int hopIndex) throws Exception {
        // Determine the junction variable: the endpoint of this hop already bound by previous tuples.
        String hopSourceVar = hop.getSource().getVariable();
        String hopSinkVar = hop.getSink().getVariable();
        Set<String> prevBoundVars = previous.get(0).keys();
        String junctionVar;
        boolean junctionIsHopSource;
        if (prevBoundVars.contains(hopSourceVar)) {
            junctionVar = hopSourceVar;
            junctionIsHopSource = true;
        } else if (prevBoundVars.contains(hopSinkVar)) {
            junctionVar = hopSinkVar;
            junctionIsHopSource = false;
        } else {
            throw new CypherUnsupportedException("hop " + hopIndex + " shares no variable with the prior results; "
                            + "disconnected patterns are not supported");
        }

        if (hop.isVariableLength()) {
            return expander.expand(hop, previous, junctionVar, junctionIsHopSource);
        }

        Set<String> frontier = buildFrontier(previous, junctionVar);
        // Intersect with identity filters on the junction endpoint.
        NodeBinding junctionBinding = junctionIsHopSource ? hop.getSource() : hop.getSink();
        if (!junctionBinding.getIdentityEquals().isEmpty()) {
            frontier.retainAll(junctionBinding.getIdentityEquals().values());
        }
        if (frontier.isEmpty()) {
            return new ArrayList<>();
        }
        if (frontier.size() > limits.getMaxFrontierSize()) {
            throw new CypherUnsupportedException("hop " + hopIndex + " frontier size " + frontier.size() + " exceeds cap of " + limits.getMaxFrontierSize());
        }

        HopTranslation translation = junctionIsHopSource ? hopTranslator.translate(hop, frontier) : hopTranslator.translateWithSinkFrontier(hop, frontier);
        List<EdgeRow> edgeRows = scanEdges(translation);
        Map<String,List<PathTuple>> index = buildIndex(previous, junctionVar);
        List<PathTuple> expanded = new ArrayList<>();
        for (EdgeRow row : edgeRows) {
            PathTuple edgeTuple = buildTupleFromEdge(hop, translation, row);
            if (edgeTuple == null) {
                continue;
            }
            String junctionValue = edgeTuple.get(junctionVar);
            List<PathTuple> matching = index.get(junctionValue);
            if (matching == null) {
                continue;
            }
            for (PathTuple prev : matching) {
                PathTuple merged = prev.merge(edgeTuple);
                if (hop.getPathVariable().isPresent()) {
                    merged = decoratePathForFixedHop(hop, translation, merged, edgeTuple);
                }
                expanded.add(merged);
            }
        }
        return expanded;
    }

    /**
     * For a fixed-length hop bound to a path variable, append the source-node
     * (when this is the first appearance of the path), the edge, and the
     * sink-node to the path geometry.
     */
    private PathTuple decoratePathForFixedHop(HopSpec hop, HopTranslation translation, PathTuple tuple) {
        return decoratePathForFixedHop(hop, translation, tuple, tuple);
    }

    private PathTuple decoratePathForFixedHop(HopSpec hop, HopTranslation translation, PathTuple tuple, PathTuple edgeBindings) {
        String pathVar = hop.getPathVariable().get();
        String sourceVar = hop.getSource().getVariable();
        String sinkVar = hop.getSink().getVariable();
        String sourceVal = edgeBindings.get(sourceVar);
        String sinkVal = edgeBindings.get(sinkVar);
        PathTuple out = tuple;
        if (out.getPath(pathVar).isEmpty()) {
            out = out.extendPath(pathVar, new PathElement.NodeElement(sourceVar, sourceVal, null));
        }
        out = out.extendPath(pathVar, new PathElement.EdgeElement(hop.getRelVariable().orElse(null), hop.getRel().getCypherType(), sourceVal, sinkVal,
                        edgeAttributes(hop, edgeBindings)));
        out = out.extendPath(pathVar, new PathElement.NodeElement(sinkVar, sinkVal, null));
        return out;
    }

    private List<PathTuple> buildLiteralFrontierTuples(HopSpec hop) {
        // Seed BFS with one tuple per source-identity literal so VariableLengthExpander
        // can produce the proper hop-source binding when scanning step 1.
        NodeBinding source = hop.getSource();
        List<PathTuple> seed = new ArrayList<>();
        for (String value : source.getIdentityEquals().values()) {
            Map<String,String> map = new LinkedHashMap<>(1);
            map.put(source.getVariable(), value);
            PathTuple t = PathTuple.of(map);
            if (hop.getPathVariable().isPresent()) {
                t = t.extendPath(hop.getPathVariable().get(), new PathElement.NodeElement(source.getVariable(), value, null));
            }
            seed.add(t);
        }
        return seed;
    }

    private Map<String,String> edgeAttributes(HopSpec hop, PathTuple edgeBindings) {
        if (hop.getRelVariable().isEmpty()) {
            return null;
        }
        String relVar = hop.getRelVariable().get();
        Map<String,String> out = new LinkedHashMap<>();
        for (String attr : hop.getRel().getAttributeMappings().keySet()) {
            String v = edgeBindings.get(relVar + "." + attr);
            if (v != null) {
                out.put(attr, v);
            }
        }
        return out;
    }

    private boolean needsEnrichment(CypherPlan plan) {
        for (Projection p : plan.getProjections()) {
            if (p.getKind() == Projection.Kind.NODE_SHARD_PROPERTY) {
                return true;
            }
            if (p.getKind() == Projection.Kind.AGGREGATE && p.getAggregateSpec().isPresent()) {
                // Aggregating over a shard property also requires enrichment.
                // Identity arguments are stored under the bare variable key already.
                p.getAggregateSpec().get().getArgumentProperty().ifPresent(prop -> {});
            }
        }
        for (HopSpec hop : plan.getHops()) {
            if (hop.getSource().requiresShardEnrichment() || hop.getSink().requiresShardEnrichment()) {
                return true;
            }
        }
        return false;
    }

    // ---- scan -----------------------------------------------------------

    List<EdgeRow> scanEdges(HopTranslation translation) throws Exception {
        BatchScanner scanner = QueryScannerHelper.createBatchScanner(client, tableName, auths, queryThreads, query);
        scanner.setRanges(translation.getRanges());

        IteratorSetting filter = new IteratorSetting(FILTER_PRIORITY,
                        EdgeFilterIterator.class.getSimpleName() + "_" + FILTER_PRIORITY, EdgeFilterIterator.class);
        filter.addOption(EdgeFilterIterator.JEXL_OPTION, translation.getFilterJexl());
        filter.addOption(EdgeFilterIterator.PROTOBUF_OPTION, "TRUE");
        filter.addOption(EdgeFilterIterator.INCLUDE_STATS_OPTION, "FALSE");
        scanner.addScanIterator(filter);

        List<EdgeRow> out = new ArrayList<>();
        try {
            for (Map.Entry<Key,Value> entry : scanner) {
                out.add(new EdgeRow(entry.getKey(), entry.getValue()));
            }
        } finally {
            scanner.close();
        }
        return out;
    }

    // ---- tuple construction from an edge row ----------------------------

    PathTuple buildTupleFromEdge(HopSpec hop, HopTranslation translation, EdgeRow row) {
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

        return PathTuple.of(tupleMap, row.getVisibility());
    }

    long edgeFingerprint(HopSpec hop, EdgeRow row) {
        EdgeKey edgeKey;
        try {
            edgeKey = EdgeKey.decode(row.getKey());
        } catch (Exception e) {
            return row.getKey().hashCode();
        }
        // Canonicalise the endpoints for an undirected relationship so the forward
        // and reverse physical rows yield identical fingerprints — required for
        // trail dedup along a single path.
        String a = edgeKey.getSourceData();
        String b = edgeKey.getSinkData();
        String low = a.compareTo(b) <= 0 ? a : b;
        String high = a.compareTo(b) <= 0 ? b : a;
        long h = 1125899906842597L; // prime
        h = 31 * h + hop.getRel().getEdgeType().hashCode();
        h = 31 * h + low.hashCode();
        h = 31 * h + high.hashCode();
        h = 31 * h + (edgeKey.getAttribute1() == null ? 0 : edgeKey.getAttribute1().hashCode());
        h = 31 * h + (edgeKey.getAttribute2() == null ? 0 : edgeKey.getAttribute2().hashCode());
        h = 31 * h + (edgeKey.getAttribute3() == null ? 0 : edgeKey.getAttribute3().hashCode());
        return h;
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
        Map<String,Set<String>> nodePropsNeeded = new LinkedHashMap<>();
        for (Projection p : plan.getProjections()) {
            if (p.getKind() == Projection.Kind.NODE_SHARD_PROPERTY) {
                nodePropsNeeded.computeIfAbsent(p.getVariable(), k -> new LinkedHashSet<>()).add(p.getProperty());
            }
            if (p.getKind() == Projection.Kind.AGGREGATE && p.getAggregateSpec().isPresent()) {
                p.getAggregateSpec().get().getArgumentVariable().ifPresent(v -> {
                    p.getAggregateSpec().get().getArgumentProperty().ifPresent(prop -> nodePropsNeeded.computeIfAbsent(v, k -> new LinkedHashSet<>()).add(prop));
                });
            }
        }
        for (HopSpec hop : plan.getHops()) {
            addShardFilterProperties(hop.getSource(), nodePropsNeeded);
            addShardFilterProperties(hop.getSink(), nodePropsNeeded);
        }
        return enrichmentService.enrich(tuples, nodePropsNeeded, plan);
    }

    private void addShardFilterProperties(NodeBinding binding, Map<String,Set<String>> nodePropsNeeded) {
        if (binding.requiresShardEnrichment()) {
            nodePropsNeeded.computeIfAbsent(binding.getVariable(), k -> new LinkedHashSet<>()).addAll(binding.getShardPropertyFilters().keySet());
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
                sb.append('');
            }
            String v = resolveProjectedValue(t, p);
            if (v != null) {
                sb.append(v);
            }
        }
        return sb.toString();
    }

    private String resolveProjectedValue(PathTuple t, Projection p) {
        switch (p.getKind()) {
            case NODE_PROPERTY:
                return t.get(p.getVariable());
            case REL_PROPERTY:
            case NODE_SHARD_PROPERTY:
                return t.get(p.getVariable() + "." + p.getProperty());
            case AGGREGATE:
                return t.get(Projection.AGGREGATE_TUPLE_KEY_PREFIX + p.getAlias());
            case PATH_OBJECT:
                // Paths aren't meaningfully comparable as strings for DISTINCT/ORDER BY;
                // a stable but opaque key keeps the algorithms happy without false dedup.
                return "path:" + p.getVariable() + "@" + System.identityHashCode(t.getPath(p.getVariable()));
            default:
                return null;
        }
    }

    private void sort(List<PathTuple> tuples, List<SortSpec> orderBy, List<Projection> projections) {
        Map<String,Projection> byAlias = new LinkedHashMap<>();
        for (Projection p : projections) {
            byAlias.put(p.getAlias(), p);
        }

        Comparator<PathTuple> comparator = null;
        for (SortSpec spec : orderBy) {
            Projection proj = byAlias.get(spec.getAlias());
            if (proj == null) {
                continue;
            }
            Comparator<PathTuple> c = comparatorFor(proj);
            if (spec.getDirection() == datawave.query.cypher.ast.SortItem.Direction.DESC) {
                c = c.reversed();
            }
            comparator = (comparator == null) ? c : comparator.thenComparing(c);
        }
        if (comparator != null) {
            tuples.sort(comparator);
        }
    }

    private static Comparator<PathTuple> comparatorFor(Projection p) {
        if (p.getKind() == Projection.Kind.AGGREGATE) {
            // Numeric ordering on aggregate result; non-numeric (e.g. MIN/MAX of strings)
            // falls back to lexicographic.
            return Comparator.comparing((PathTuple t) -> aggregateAsBigDecimal(t, p), Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing((PathTuple t) -> aggregateAsString(t, p), Comparator.nullsLast(Comparator.naturalOrder()));
        }
        return Comparator.comparing((PathTuple t) -> stringValueFor(t, p), Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static java.math.BigDecimal aggregateAsBigDecimal(PathTuple t, Projection p) {
        String raw = t.get(Projection.AGGREGATE_TUPLE_KEY_PREFIX + p.getAlias());
        if (raw == null) {
            return null;
        }
        try {
            return new java.math.BigDecimal(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String aggregateAsString(PathTuple t, Projection p) {
        return t.get(Projection.AGGREGATE_TUPLE_KEY_PREFIX + p.getAlias());
    }

    private static String stringValueFor(PathTuple t, Projection p) {
        if (p.getKind() == Projection.Kind.NODE_PROPERTY) {
            return t.get(p.getVariable());
        }
        if (p.getKind() == Projection.Kind.PATH_OBJECT) {
            return "path:" + System.identityHashCode(t.getPath(p.getVariable()));
        }
        return t.get(p.getVariable() + "." + p.getProperty());
    }
}
