package datawave.query.cypher.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import datawave.query.cypher.ast.SourceLocation;

/**
 * ANTLR error listener that accumulates lexer/parser errors instead of
 * printing them to stderr, so the facade can surface them all at once as a
 * single {@link CypherSyntaxException}.
 */
final class CollectingErrorListener extends BaseErrorListener {

    private final List<CypherSyntaxException.ParseMessage> messages = new ArrayList<>();

    @Override
    public void syntaxError(Recognizer<?,?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
        messages.add(new CypherSyntaxException.ParseMessage(new SourceLocation(line, charPositionInLine + 1), msg));
    }

    boolean hasErrors() {
        return !messages.isEmpty();
    }

    List<CypherSyntaxException.ParseMessage> drain() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }
}
