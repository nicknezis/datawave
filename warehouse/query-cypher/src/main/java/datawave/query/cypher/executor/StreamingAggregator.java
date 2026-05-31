package datawave.query.cypher.executor;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import datawave.query.cypher.planner.AggregateSpec;
import datawave.query.cypher.planner.CypherUnsupportedException;
import datawave.query.cypher.planner.GroupingSpec;
import datawave.query.cypher.planner.Projection;

/**
 * Streaming GROUP BY + aggregation over a stream of {@link PathTuple}s.
 *
 * <p>
 * Group key = ordered tuple of resolved values for each non-aggregate projection. One accumulator row per group; per-row argument values are read from the
 * PathTuple under {@code "variable.property"} (or directly under the variable for identity projections).
 *
 * <p>
 * Output tuples carry:
 * <ul>
 * <li>group-by column values under their natural variable keys (so {@code MultiHopExecutor.resolveProjectedValue} resolves them unchanged), and</li>
 * <li>aggregate results under {@code "__agg__.<alias>"} keys so the transformer can read them while keeping the rest of the post- processing pipeline unaware
 * of aggregation.</li>
 * </ul>
 *
 * <p>
 * Composing every contributing tuple's {@link PathTuple#getVisibilities() visibilities} keeps the per-group composite-row marking honest.
 */
final class StreamingAggregator {

    private static final Logger log = LoggerFactory.getLogger(StreamingAggregator.class);

    private static final MathContext AVG_CONTEXT = new MathContext(16, RoundingMode.HALF_UP);

    private final GroupingSpec spec;
    private final int maxGroups;

    StreamingAggregator(GroupingSpec spec, int maxGroups) {
        this.spec = spec;
        this.maxGroups = maxGroups;
    }

    List<PathTuple> aggregate(List<PathTuple> tuples) {
        Map<List<String>,GroupState> groups = new LinkedHashMap<>();
        for (PathTuple t : tuples) {
            List<String> key = buildGroupKey(t);
            GroupState state = groups.get(key);
            if (state == null) {
                if (groups.size() >= maxGroups) {
                    throw new CypherUnsupportedException("aggregation group count exceeds cap of " + maxGroups
                                    + "; either raise CypherQueryConfiguration.maxAggregateGroups or narrow the query");
                }
                state = new GroupState(t);
                groups.put(key, state);
            } else {
                state.merge(t);
            }
            for (Projection agg : spec.getAggregates()) {
                applyAggregate(state, agg, t);
            }
        }
        List<PathTuple> out = new ArrayList<>(groups.size());
        for (GroupState state : groups.values()) {
            out.add(state.toTuple(spec));
        }
        if (log.isDebugEnabled()) {
            log.debug("aggregated {} input tuples into {} group(s)", tuples.size(), out.size());
        }
        return out;
    }

    private List<String> buildGroupKey(PathTuple t) {
        List<String> key = new ArrayList<>(spec.getGroupByKeys().size());
        for (Projection p : spec.getGroupByKeys()) {
            key.add(resolveGroupKeyValue(t, p));
        }
        return key;
    }

    private static String resolveGroupKeyValue(PathTuple t, Projection p) {
        if (p.getKind() == Projection.Kind.NODE_PROPERTY) {
            return t.get(p.getVariable());
        }
        if (p.getKind() == Projection.Kind.PATH_OBJECT) {
            // Path objects cannot serve as a deterministic group key; the planner
            // rejects mixing them with aggregates, but defensive null is safest.
            return null;
        }
        // NODE_SHARD_PROPERTY / REL_PROPERTY both live under "variable.property"
        return t.get(p.getVariable() + "." + p.getProperty());
    }

    private static void applyAggregate(GroupState state, Projection projection, PathTuple t) {
        AggregateSpec aggSpec = projection.getAggregateSpec().orElseThrow(() -> new IllegalStateException("AGGREGATE projection missing AggregateSpec"));
        Accumulator acc = state.accumulators.computeIfAbsent(projection.getAlias(), k -> Accumulator.forSpec(aggSpec));
        if (aggSpec.getFunc() == AggregateSpec.Func.COUNT_STAR) {
            acc.observe(null);
            return;
        }
        // Identity properties live under "var"; non-identity properties live under "var.prop".
        // Try the qualified key first, fall back to the bare variable for identity props.
        String value = t.get(aggSpec.getArgumentTupleKey());
        if (value == null && aggSpec.getArgumentVariable().isPresent()) {
            value = t.get(aggSpec.getArgumentVariable().get());
        }
        acc.observe(value);
    }

    // ---- per-group state ------------------------------------------------

    private static final class GroupState {
        final PathTuple firstTuple;
        final Map<String,Accumulator> accumulators = new LinkedHashMap<>();
        PathTuple visibilityCarrier;

        GroupState(PathTuple first) {
            this.firstTuple = first;
            this.visibilityCarrier = first;
        }

        void merge(PathTuple t) {
            if (!t.getVisibilities().isEmpty()) {
                this.visibilityCarrier = visibilityCarrier.merge(t);
            }
        }

        PathTuple toTuple(GroupingSpec spec) {
            Map<String,String> values = new LinkedHashMap<>();
            // Copy group-by column values from the first contributing tuple — every
            // tuple in the group has identical values by construction.
            for (Projection p : spec.getGroupByKeys()) {
                if (p.getKind() == Projection.Kind.NODE_PROPERTY) {
                    String key = p.getVariable();
                    if (firstTuple.contains(key)) {
                        values.put(key, firstTuple.get(key));
                    }
                } else {
                    String key = p.getVariable() + "." + p.getProperty();
                    if (firstTuple.contains(key)) {
                        values.put(key, firstTuple.get(key));
                    }
                }
            }
            // Stash aggregate results under the reserved prefix so the executor's
            // existing resolveProjectedValue can read them with no special case.
            for (Map.Entry<String,Accumulator> e : accumulators.entrySet()) {
                values.put(Projection.AGGREGATE_TUPLE_KEY_PREFIX + e.getKey(), e.getValue().finalValue());
            }
            PathTuple result = PathTuple.of(values);
            for (org.apache.accumulo.core.security.ColumnVisibility cv : visibilityCarrier.getVisibilities()) {
                result = result.withVisibility(cv);
            }
            return result;
        }
    }

    // ---- accumulators ---------------------------------------------------

    private abstract static class Accumulator {
        abstract void observe(String value);

        abstract String finalValue();

        static Accumulator forSpec(AggregateSpec spec) {
            switch (spec.getFunc()) {
                case COUNT_STAR:
                    return new CountStar();
                case COUNT:
                    return spec.isDistinct() ? new CountDistinct() : new CountNonNull();
                case SUM:
                    return new Sum();
                case AVG:
                    return new Avg();
                case MIN:
                    return new MinMax(true);
                case MAX:
                    return new MinMax(false);
                default:
                    throw new IllegalStateException("unhandled aggregate func " + spec.getFunc());
            }
        }
    }

    private static final class CountStar extends Accumulator {
        long count;

        @Override
        void observe(String value) {
            count++;
        }

        @Override
        String finalValue() {
            return Long.toString(count);
        }
    }

    private static final class CountNonNull extends Accumulator {
        long count;

        @Override
        void observe(String value) {
            if (value != null) {
                count++;
            }
        }

        @Override
        String finalValue() {
            return Long.toString(count);
        }
    }

    private static final class CountDistinct extends Accumulator {
        final Set<String> seen = new HashSet<>();

        @Override
        void observe(String value) {
            if (value != null) {
                seen.add(value);
            }
        }

        @Override
        String finalValue() {
            return Long.toString(seen.size());
        }
    }

    private static final class Sum extends Accumulator {
        BigDecimal sum = BigDecimal.ZERO;
        boolean anyValue;

        @Override
        void observe(String value) {
            BigDecimal n = parseNumeric(value);
            if (n != null) {
                sum = sum.add(n);
                anyValue = true;
            }
        }

        @Override
        String finalValue() {
            return anyValue ? sum.toPlainString() : null;
        }
    }

    private static final class Avg extends Accumulator {
        BigDecimal sum = BigDecimal.ZERO;
        long count;

        @Override
        void observe(String value) {
            BigDecimal n = parseNumeric(value);
            if (n != null) {
                sum = sum.add(n);
                count++;
            }
        }

        @Override
        String finalValue() {
            if (count == 0) {
                return null;
            }
            return sum.divide(BigDecimal.valueOf(count), AVG_CONTEXT).toPlainString();
        }
    }

    private static final class MinMax extends Accumulator {
        final boolean isMin;
        String extreme;

        MinMax(boolean isMin) {
            this.isMin = isMin;
        }

        @Override
        void observe(String value) {
            if (value == null) {
                return;
            }
            if (extreme == null) {
                extreme = value;
                return;
            }
            int cmp = value.compareTo(extreme);
            if ((isMin && cmp < 0) || (!isMin && cmp > 0)) {
                extreme = value;
            }
        }

        @Override
        String finalValue() {
            return extreme;
        }
    }

    private static BigDecimal parseNumeric(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            log.debug("aggregate skipped non-numeric value: {}", value);
            return null;
        }
    }
}
