package datawave.query.cypher.parser;

import java.util.Collections;
import java.util.List;

import datawave.query.cypher.ast.SourceLocation;

/**
 * Thrown when the input text fails to parse against the Cypher grammar. Carries
 * the list of collected {@link ParseMessage}s so callers can surface every
 * problem at once instead of whack-a-mole through repeated parse attempts.
 */
public class CypherSyntaxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<ParseMessage> messages;

    public CypherSyntaxException(List<ParseMessage> messages) {
        super(buildSummary(messages));
        this.messages = Collections.unmodifiableList(messages);
    }

    public List<ParseMessage> getMessages() {
        return messages;
    }

    private static String buildSummary(List<ParseMessage> messages) {
        if (messages.isEmpty()) {
            return "Cypher syntax error";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Cypher syntax error (").append(messages.size()).append(" issue").append(messages.size() == 1 ? "" : "s").append("):");
        for (ParseMessage m : messages) {
            sb.append("\n  at ").append(m.getLocation()).append(": ").append(m.getMessage());
        }
        return sb.toString();
    }

    /** A single parser error, with its location in the original text. */
    public static final class ParseMessage {
        private final SourceLocation location;
        private final String message;

        public ParseMessage(SourceLocation location, String message) {
            this.location = location;
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
