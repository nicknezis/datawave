package datawave.query.cypher;

import java.util.Objects;

import datawave.query.cypher.ast.CypherQuery;
import datawave.query.cypher.parser.CypherParser;
import datawave.query.cypher.semantic.BindingTable;
import datawave.query.cypher.semantic.SemanticAnalyzer;
import datawave.query.cypher.util.AstPrinter;

/**
 * M0 public facade: turns Cypher text into a validated AST and optionally dumps it. Later milestones add logical planning, physical translation, and the
 * CypherQueryLogic wiring; those extensions will consume the AST + binding table surfaced here without changing this entry point.
 *
 * Thread-safe: holds no per-call state.
 */
public final class CypherFrontEnd {

    private final CypherParser parser = new CypherParser();

    public Analysis analyze(String text) {
        Objects.requireNonNull(text, "text");
        CypherQuery ast = parser.parse(text);
        BindingTable bindings = new SemanticAnalyzer().analyze(ast);
        return new Analysis(ast, bindings);
    }

    /**
     * Renders the M0 equivalent of {@code QueryPlanner#getPlannedScript}: a human-readable dump of the parsed AST. When the Cypher planner lands in M1+, this
     * will grow into a real physical-plan rendering.
     */
    public String getPlannedScript(String text) {
        return AstPrinter.print(analyze(text).getAst());
    }

    /** Result bundle returned by {@link #analyze(String)}. */
    public static final class Analysis {
        private final CypherQuery ast;
        private final BindingTable bindingTable;

        public Analysis(CypherQuery ast, BindingTable bindingTable) {
            this.ast = ast;
            this.bindingTable = bindingTable;
        }

        public CypherQuery getAst() {
            return ast;
        }

        public BindingTable getBindingTable() {
            return bindingTable;
        }
    }
}
