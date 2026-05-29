package datawave.query.cypher.semantic;

import java.util.Collections;
import java.util.List;

import datawave.query.cypher.ast.SourceLocation;

/**
 * Thrown when a query parses cleanly but fails semantic analysis. As with the syntax exception, carries every collected issue so callers see all of them at
 * once.
 */
public class SemanticException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<Issue> issues;

    public SemanticException(List<Issue> issues) {
        super(buildSummary(issues));
        this.issues = Collections.unmodifiableList(issues);
    }

    public List<Issue> getIssues() {
        return issues;
    }

    private static String buildSummary(List<Issue> issues) {
        StringBuilder sb = new StringBuilder("Cypher semantic error");
        if (!issues.isEmpty()) {
            sb.append(" (").append(issues.size()).append(" issue").append(issues.size() == 1 ? "" : "s").append("):");
            for (Issue i : issues) {
                sb.append("\n  at ").append(i.getLocation()).append(": ").append(i.getMessage());
            }
        }
        return sb.toString();
    }

    public static final class Issue {
        private final SourceLocation location;
        private final String message;

        public Issue(SourceLocation location, String message) {
            this.location = location == null ? SourceLocation.UNKNOWN : location;
            this.message = message;
        }

        public SourceLocation getLocation() {
            return location;
        }

        public String getMessage() {
            return message;
        }
    }
}
