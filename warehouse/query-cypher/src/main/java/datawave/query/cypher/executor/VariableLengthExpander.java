package datawave.query.cypher.executor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import datawave.query.cypher.physical.HopTranslation;
import datawave.query.cypher.physical.HopTranslator;
import datawave.query.cypher.planner.CypherUnsupportedException;
import datawave.query.cypher.planner.HopSpec;

/**
 * Client-orchestrated breadth-first expansion of a single {@link HopSpec#isVariableLength() variable-length} hop.
 *
 * <p>
 * Each BFS step does one edge scan against the previous step's sink frontier, joins via the junction variable, and emits a tuple for every path. A per-path
 * edge-fingerprint set enforces Cypher's relationship-isomorphism (no repeated relationship along a single path within the same variable-length segment).
 *
 * <p>
 * Termination:
 * <ul>
 * <li>Runs {@code step ∈ [1, upper]} where {@code upper} is {@link HopSpec#getUpper()}; the planner caps {@code upper} at configured
 * {@code maxVariableLengthUpper}.</li>
 * <li>Stops early when the frontier collapses to empty.</li>
 * <li>Per-step frontier size is bounded by {@code maxFrontierSize}; per-path edge history is bounded by {@code maxPathEdgeHistory}. Exceeding either throws
 * {@link CypherUnsupportedException}.</li>
 * </ul>
 *
 * <p>
 * Results from every step in {@code [lower, upper]} are accumulated and returned together.
 */
final class VariableLengthExpander {

    private static final Logger log = LoggerFactory.getLogger(VariableLengthExpander.class);

    private final HopTranslator translator;
    private final EdgeScanner edgeScanner;
    private final EdgeTupleFactory tupleFactory;
    private final ExecutorLimits limits;

    /** Scans edge rows for one BFS step. Abstracted so unit tests can stub it. */
    interface EdgeScanner {
        List<EdgeRow> scan(HopTranslation translation) throws Exception;
    }

    /** Materialises one scanned edge row into a {@link PathTuple} representing that edge. */
    interface EdgeTupleFactory {
        PathTuple build(HopSpec hop, HopTranslation translation, EdgeRow row);

        /**
         * Stable 64-bit fingerprint of the edge identity; used by the trail-history dedup. Two physical rows for the same logical edge (forward + reverse for
         * an undirected relationship) must hash to the same value.
         */
        long edgeFingerprint(HopSpec hop, EdgeRow row);
    }

    VariableLengthExpander(HopTranslator translator, EdgeScanner edgeScanner, EdgeTupleFactory tupleFactory, ExecutorLimits limits) {
        this.translator = translator;
        this.edgeScanner = edgeScanner;
        this.tupleFactory = tupleFactory;
        this.limits = limits;
    }

    /**
     * Expands {@code [lower, upper]} steps starting from {@code incoming}.
     *
     * @param hop
     *            the variable-length hop spec; must satisfy {@link HopSpec#isVariableLength()}
     * @param incoming
     *            tuples flowing into this hop (driven by prior hops or by the initial scan when this is hop 0)
     * @param junctionVar
     *            the variable in {@code incoming} that joins to the source side of this hop (typically {@code hop.source.variable} for sequential expansions,
     *            or {@code hop.sink.variable} when the prior hop ended at this hop's sink)
     * @param junctionIsHopSource
     *            true if {@code junctionVar} corresponds to the hop's source endpoint, false if it corresponds to the sink (which triggers the undirected
     *            sink-frontier translation path)
     */
    List<PathTuple> expand(HopSpec hop, List<PathTuple> incoming, String junctionVar, boolean junctionIsHopSource) throws Exception {
        if (!hop.isVariableLength()) {
            throw new IllegalArgumentException("VariableLengthExpander requires a variable-length hop");
        }
        int lower = hop.getLower().getAsInt();
        int upper = hop.getUpper().getAsInt();
        if (upper > limits.getMaxVariableLengthUpper()) {
            throw new CypherUnsupportedException("variable-length upper bound " + upper + " exceeds executor cap " + limits.getMaxVariableLengthUpper());
        }
        String pathVar = hop.getPathVariable().orElse(null);
        String sourceVarName = junctionIsHopSource ? hop.getSource().getVariable() : hop.getSink().getVariable();
        String sinkVarName = junctionIsHopSource ? hop.getSink().getVariable() : hop.getSource().getVariable();

        List<PathTuple> current = incoming;
        List<PathTuple> results = new ArrayList<>();

        for (int step = 1; step <= upper; step++) {
            if (current.isEmpty()) {
                break;
            }
            Set<String> frontier = collectFrontier(current, junctionVar);
            if (frontier.isEmpty()) {
                break;
            }
            if (frontier.size() > limits.getMaxFrontierSize()) {
                throw new CypherUnsupportedException("BFS frontier of " + frontier.size() + " exceeds cap of " + limits.getMaxFrontierSize() + " at step "
                                + step + " of variable-length expansion");
            }
            HopTranslation translation = junctionIsHopSource ? translator.translate(hop, frontier) : translator.translateWithSinkFrontier(hop, frontier);
            List<EdgeRow> edgeRows = edgeScanner.scan(translation);

            // Index incoming tuples by junction value for the join.
            Map<String,List<PathTuple>> index = indexByVariable(current, junctionVar);
            List<PathTuple> expanded = new ArrayList<>();
            for (EdgeRow row : edgeRows) {
                PathTuple edgeTuple = tupleFactory.build(hop, translation, row);
                if (edgeTuple == null) {
                    continue;
                }
                long edgeFp = tupleFactory.edgeFingerprint(hop, row);
                String joinValue = edgeTuple.get(sourceVarName);
                if (joinValue == null) {
                    continue;
                }
                List<PathTuple> matching = index.get(joinValue);
                if (matching == null) {
                    continue;
                }
                for (PathTuple prev : matching) {
                    if (pathVar != null && prev.containsEdgeId(pathVar, edgeFp)) {
                        // Trail semantics: this edge has already been walked along this path.
                        continue;
                    }
                    PathTuple next = prev.merge(edgeTuple);
                    if (pathVar != null) {
                        if (prev.pathEdgeHistorySize(pathVar) >= limits.getMaxPathEdgeHistory()) {
                            throw new CypherUnsupportedException("per-path edge-trail history exceeds cap of " + limits.getMaxPathEdgeHistory()
                                            + " for path variable '" + pathVar + "'");
                        }
                        next = next.recordEdgeId(pathVar, edgeFp);
                        next = next.extendPath(pathVar, new PathElement.EdgeElement(hop.getRelVariable().orElse(null), hop.getRel().getCypherType(),
                                        edgeTuple.get(sourceVarName), edgeTuple.get(sinkVarName), edgeAttributes(hop, edgeTuple)));
                        next = next.extendPath(pathVar, new PathElement.NodeElement(sinkVarName, edgeTuple.get(sinkVarName), null));
                    }
                    expanded.add(next);
                }
            }
            if (step >= lower) {
                results.addAll(expanded);
            }
            current = expanded;
        }
        if (log.isDebugEnabled()) {
            log.debug("variable-length expansion produced {} tuples across [{}..{}]", results.size(), lower, upper);
        }
        return results;
    }

    private Set<String> collectFrontier(List<PathTuple> tuples, String variable) {
        Set<String> frontier = new LinkedHashSet<>(tuples.size());
        for (PathTuple t : tuples) {
            String v = t.get(variable);
            if (v != null) {
                frontier.add(v);
            }
        }
        return frontier;
    }

    private Map<String,List<PathTuple>> indexByVariable(List<PathTuple> tuples, String variable) {
        Map<String,List<PathTuple>> index = new LinkedHashMap<>();
        for (PathTuple t : tuples) {
            String v = t.get(variable);
            if (v != null) {
                index.computeIfAbsent(v, k -> new ArrayList<>()).add(t);
            }
        }
        return index;
    }

    private Map<String,String> edgeAttributes(HopSpec hop, PathTuple edgeTuple) {
        if (hop.getRelVariable().isEmpty()) {
            return null;
        }
        String relVar = hop.getRelVariable().get();
        Map<String,String> out = new LinkedHashMap<>();
        for (String attr : hop.getRel().getAttributeMappings().keySet()) {
            String value = edgeTuple.get(relVar + "." + attr);
            if (value != null) {
                out.put(attr, value);
            }
        }
        return out;
    }
}
