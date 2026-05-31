package datawave.query.cypher.planner;

/**
 * Thrown when a Cypher query parses cleanly and is semantically valid, but uses a feature the current milestone of {@code CypherQueryLogic} does not yet
 * support. The message names the feature and the milestone in which it is planned, so authors get an actionable error rather than an opaque "couldn't run"
 * failure.
 */
public class CypherUnsupportedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CypherUnsupportedException(String message) {
        super(message);
    }

    public CypherUnsupportedException(String message, Throwable cause) {
        super(message, cause);
    }
}
