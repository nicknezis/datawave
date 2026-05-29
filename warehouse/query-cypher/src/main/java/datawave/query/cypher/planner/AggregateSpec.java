package datawave.query.cypher.planner;

import java.util.Objects;
import java.util.Optional;

/**
 * Describes an aggregating projection: which function to apply, which bound variable's property feeds the accumulator, and whether DISTINCT pre-dedup applies.
 * {@code COUNT(*)} uses {@link Func#COUNT_STAR} with empty variable and property; every other function has a single argument expression that must resolve to a
 * {@code variable.property} reference.
 */
public final class AggregateSpec {

    public enum Func {
        /** {@code count(*)} — counts rows per group. */
        COUNT_STAR,
        /** {@code count(x)} / {@code count(DISTINCT x)} — counts non-null values. */
        COUNT,
        /** {@code sum(x)} — numeric, accumulated as {@link java.math.BigDecimal}. */
        SUM,
        /** {@code avg(x)} — numeric, returned as {@link java.math.BigDecimal}. */
        AVG,
        /** {@code min(x)} — lexicographic over the on-disk string value in v1. */
        MIN,
        /** {@code max(x)} — lexicographic over the on-disk string value in v1. */
        MAX
    }

    private final Func func;
    private final String argumentVariable;
    private final String argumentProperty;
    private final boolean distinct;

    public AggregateSpec(Func func, String argumentVariable, String argumentProperty, boolean distinct) {
        this.func = Objects.requireNonNull(func, "func");
        this.argumentVariable = argumentVariable;
        this.argumentProperty = argumentProperty;
        this.distinct = distinct;
        if (func == Func.COUNT_STAR) {
            if (argumentVariable != null || argumentProperty != null) {
                throw new IllegalArgumentException("COUNT(*) takes no argument variable/property");
            }
            if (distinct) {
                throw new IllegalArgumentException("COUNT(DISTINCT *) is not allowed");
            }
        } else {
            Objects.requireNonNull(argumentVariable, "argumentVariable required for non-COUNT_STAR aggregate");
            Objects.requireNonNull(argumentProperty, "argumentProperty required for non-COUNT_STAR aggregate");
        }
    }

    public Func getFunc() {
        return func;
    }

    public Optional<String> getArgumentVariable() {
        return Optional.ofNullable(argumentVariable);
    }

    public Optional<String> getArgumentProperty() {
        return Optional.ofNullable(argumentProperty);
    }

    public boolean isDistinct() {
        return distinct;
    }

    /**
     * The key used in {@link datawave.query.cypher.executor.PathTuple} to read the per-row argument value during aggregation. {@code null} for
     * {@link Func#COUNT_STAR}.
     */
    public String getArgumentTupleKey() {
        if (func == Func.COUNT_STAR) {
            return null;
        }
        return argumentVariable + "." + argumentProperty;
    }
}
