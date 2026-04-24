package datawave.query.cypher.parser;

import java.util.Objects;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import datawave.query.cypher.ast.CypherQuery;
import datawave.query.cypher.parser.antlr.CypherLexer;
import datawave.query.cypher.parser.antlr.CypherParser.CypherContext;

/**
 * Public entry point to the M0 Cypher front-end: turns a query string into a
 * {@link CypherQuery} AST, surfacing all syntax errors at once through
 * {@link CypherSyntaxException}. Downstream semantic analysis lives in
 * {@link datawave.query.cypher.semantic.SemanticAnalyzer} and is intentionally
 * invoked separately so callers can choose to pretty-print or otherwise
 * inspect the raw AST before accepting it as valid.
 */
public final class CypherParser {

    public CypherQuery parse(String text) {
        Objects.requireNonNull(text, "text");

        CypherLexer lexer = new CypherLexer(CharStreams.fromString(text));
        CollectingErrorListener lexErrors = new CollectingErrorListener();
        lexer.removeErrorListeners();
        lexer.addErrorListener(lexErrors);

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        datawave.query.cypher.parser.antlr.CypherParser antlrParser = new datawave.query.cypher.parser.antlr.CypherParser(tokens);
        CollectingErrorListener parseErrors = new CollectingErrorListener();
        antlrParser.removeErrorListeners();
        antlrParser.addErrorListener(parseErrors);

        CypherContext parseTree = antlrParser.cypher();

        if (lexErrors.hasErrors() || parseErrors.hasErrors()) {
            java.util.List<CypherSyntaxException.ParseMessage> all = new java.util.ArrayList<>();
            all.addAll(lexErrors.snapshot());
            all.addAll(parseErrors.snapshot());
            throw new CypherSyntaxException(all);
        }

        return new AstBuilder().buildQuery(parseTree);
    }
}
