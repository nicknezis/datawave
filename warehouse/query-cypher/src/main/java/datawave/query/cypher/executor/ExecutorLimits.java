package datawave.query.cypher.executor;

/**
 * Per-query execution caps passed to {@link MultiHopExecutor} from {@link datawave.query.cypher.config.CypherQueryConfiguration}. Bundling them as one
 * immutable record keeps {@code MultiHopExecutor}'s constructor stable as more knobs are added.
 */
public final class ExecutorLimits {

    public static final ExecutorLimits DEFAULTS = new ExecutorLimits(5, 100_000, 100_000, 64);

    private final int maxVariableLengthUpper;
    private final int maxFrontierSize;
    private final int maxAggregateGroups;
    private final int maxPathEdgeHistory;

    public ExecutorLimits(int maxVariableLengthUpper, int maxFrontierSize, int maxAggregateGroups, int maxPathEdgeHistory) {
        if (maxVariableLengthUpper < 1) {
            throw new IllegalArgumentException("maxVariableLengthUpper must be >= 1");
        }
        if (maxFrontierSize < 1) {
            throw new IllegalArgumentException("maxFrontierSize must be >= 1");
        }
        if (maxAggregateGroups < 1) {
            throw new IllegalArgumentException("maxAggregateGroups must be >= 1");
        }
        if (maxPathEdgeHistory < 1) {
            throw new IllegalArgumentException("maxPathEdgeHistory must be >= 1");
        }
        this.maxVariableLengthUpper = maxVariableLengthUpper;
        this.maxFrontierSize = maxFrontierSize;
        this.maxAggregateGroups = maxAggregateGroups;
        this.maxPathEdgeHistory = maxPathEdgeHistory;
    }

    public int getMaxVariableLengthUpper() {
        return maxVariableLengthUpper;
    }

    public int getMaxFrontierSize() {
        return maxFrontierSize;
    }

    public int getMaxAggregateGroups() {
        return maxAggregateGroups;
    }

    public int getMaxPathEdgeHistory() {
        return maxPathEdgeHistory;
    }
}
