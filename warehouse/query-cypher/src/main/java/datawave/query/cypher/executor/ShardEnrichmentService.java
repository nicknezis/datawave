package datawave.query.cypher.executor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import datawave.query.cypher.planner.CypherPlan;

/**
 * Resolves non-identity node properties by performing batched lookups against
 * the shard/event table via a ShardQueryLogic prototype bean.
 *
 * <p>After enrichment, the supplied {@link PathTuple PathTuples} are extended
 * with {@code "variable.property"} keys for each requested property.  Tuples
 * where the target node is not visible (returns no event rows under the
 * caller's authorizations) are dropped, consistent with the visibility
 * contract described in feasibility plan decision #1.
 *
 * <p>M2 implementation note: the full ShardQueryLogic wiring is deferred to M4
 * (production wiring milestone).  This class provides the interface consumed by
 * {@link MultiHopExecutor}; a concrete production implementation will be added
 * in a later PR.  Callers that set up the executor with a {@code null}
 * enrichment service will receive an {@link IllegalStateException} if
 * {@link Projection.Kind#NODE_SHARD_PROPERTY} projections are present in the
 * plan.
 *
 * @see MultiHopExecutor
 */
public interface ShardEnrichmentService {

    /**
     * Enriches each tuple with non-identity property values for the specified
     * node variables.
     *
     * @param tuples the in-progress path tuples, one per candidate path
     * @param nodePropsNeeded map of {@code varName → set of Cypher property names}
     *        for which on-disk values must be fetched
     * @param plan the logical plan (for schema-version and data-type context)
     * @return the subset of tuples for which all required node lookups
     *         succeeded (nodes invisible under current auths are dropped),
     *         extended with {@code "variable.property"} entries for each
     *         fetched property
     */
    List<PathTuple> enrich(List<PathTuple> tuples, Map<String,Set<String>> nodePropsNeeded, CypherPlan plan);
}
