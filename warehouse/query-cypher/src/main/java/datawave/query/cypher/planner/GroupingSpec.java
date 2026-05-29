package datawave.query.cypher.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Aggregation directive for the RETURN clause: the (implicit) GROUP BY key columns and the aggregating projections to compute per group. Cypher infers GROUP BY
 * from non-aggregate RETURN items — {@code RETURN a.name, count(b)} groups by {@code a.name} and counts {@code b} per group.
 *
 * <p>
 * Both lists reference the {@link Projection} objects already on the {@link CypherPlan}; the executor uses them to (a) build a stable per-row group key, and
 * (b) drive accumulators.
 */
public final class GroupingSpec {

    private final List<Projection> groupByKeys;
    private final List<Projection> aggregates;

    public GroupingSpec(List<Projection> groupByKeys, List<Projection> aggregates) {
        this.groupByKeys = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(groupByKeys, "groupByKeys")));
        this.aggregates = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(aggregates, "aggregates")));
        if (aggregates.isEmpty()) {
            throw new IllegalArgumentException("at least one aggregate is required; non-aggregating queries should leave groupingSpec absent");
        }
    }

    public List<Projection> getGroupByKeys() {
        return groupByKeys;
    }

    public List<Projection> getAggregates() {
        return aggregates;
    }
}
