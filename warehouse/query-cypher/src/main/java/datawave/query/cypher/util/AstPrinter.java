package datawave.query.cypher.util;

import java.util.List;
import java.util.Map;

import datawave.query.cypher.ast.BinaryExpression;
import datawave.query.cypher.ast.CypherQuery;
import datawave.query.cypher.ast.Expression;
import datawave.query.cypher.ast.FunctionCallExpression;
import datawave.query.cypher.ast.LabelCheckExpression;
import datawave.query.cypher.ast.LiteralExpression;
import datawave.query.cypher.ast.MatchClause;
import datawave.query.cypher.ast.NodePattern;
import datawave.query.cypher.ast.ParameterExpression;
import datawave.query.cypher.ast.Pattern;
import datawave.query.cypher.ast.PatternElement;
import datawave.query.cypher.ast.ProjectionItem;
import datawave.query.cypher.ast.PropertiesExpression;
import datawave.query.cypher.ast.PropertyExpression;
import datawave.query.cypher.ast.ReadingClause;
import datawave.query.cypher.ast.RelationshipPattern;
import datawave.query.cypher.ast.ReturnClause;
import datawave.query.cypher.ast.SingleQuery;
import datawave.query.cypher.ast.SortItem;
import datawave.query.cypher.ast.UnaryExpression;
import datawave.query.cypher.ast.VariableExpression;
import datawave.query.cypher.ast.WithClause;

/**
 * Indented textual dump of a parsed {@link CypherQuery}. Serves as M0's implementation of the "getPlannedScript dumps parsed AST" milestone deliverable:
 * downstream planner stages will replace this with a physical plan, but for M0 the AST dump is what callers get.
 *
 * The format is intended for humans and tests. It is not round-trippable back into Cypher text — if that's ever needed, a separate serializer should be added
 * rather than overloading this printer.
 */
public final class AstPrinter {

    private static final String INDENT = "  ";

    private final StringBuilder out = new StringBuilder();
    private int depth;

    private AstPrinter() {}

    public static String print(CypherQuery query) {
        AstPrinter p = new AstPrinter();
        p.printQuery(query);
        return p.out.toString();
    }

    private void printQuery(CypherQuery query) {
        line("Query");
        depth++;
        for (int i = 0; i < query.getBranches().size(); i++) {
            if (i > 0) {
                String label = query.getUnionAll().get(i - 1) ? "UnionAll" : "Union";
                line(label);
            }
            printSingleQuery(query.getBranches().get(i));
        }
        depth--;
    }

    private void printSingleQuery(SingleQuery query) {
        line("SingleQuery");
        depth++;
        for (ReadingClause rc : query.getReadingClauses()) {
            if (rc instanceof MatchClause) {
                printMatch((MatchClause) rc);
            } else if (rc instanceof WithClause) {
                printWith((WithClause) rc);
            }
        }
        query.getReturnClause().ifPresent(this::printReturn);
        depth--;
    }

    private void printMatch(MatchClause m) {
        line((m.isOptional() ? "OptionalMatch" : "Match"));
        depth++;
        for (Pattern p : m.getPatterns()) {
            printPattern(p);
        }
        m.getWhere().ifPresent(w -> {
            line("Where");
            depth++;
            printExpression(w);
            depth--;
        });
        depth--;
    }

    private void printWith(WithClause w) {
        line("With" + (w.isDistinct() ? " DISTINCT" : ""));
        depth++;
        printProjections(w.isProjectAll(), w.getProjections());
        printOrderBy(w.getOrderBy());
        w.getSkip().ifPresent(s -> prefixLine("Skip ", s));
        w.getLimit().ifPresent(l -> prefixLine("Limit ", l));
        w.getWhere().ifPresent(expr -> {
            line("Where");
            depth++;
            printExpression(expr);
            depth--;
        });
        depth--;
    }

    private void printReturn(ReturnClause r) {
        line("Return" + (r.isDistinct() ? " DISTINCT" : ""));
        depth++;
        printProjections(r.isProjectAll(), r.getProjections());
        printOrderBy(r.getOrderBy());
        r.getSkip().ifPresent(s -> prefixLine("Skip ", s));
        r.getLimit().ifPresent(l -> prefixLine("Limit ", l));
        depth--;
    }

    private void printProjections(boolean projectAll, List<ProjectionItem> items) {
        if (projectAll) {
            line("ProjectAll");
        }
        for (ProjectionItem item : items) {
            line("Projection" + item.getAlias().map(a -> " AS " + a).orElse(""));
            depth++;
            printExpression(item.getExpression());
            depth--;
        }
    }

    private void printOrderBy(List<SortItem> order) {
        if (order.isEmpty()) {
            return;
        }
        line("OrderBy");
        depth++;
        for (SortItem s : order) {
            line("SortItem " + s.getDirection());
            depth++;
            printExpression(s.getExpression());
            depth--;
        }
        depth--;
    }

    private void prefixLine(String prefix, Expression expr) {
        line(prefix);
        depth++;
        printExpression(expr);
        depth--;
    }

    private void printPattern(Pattern p) {
        line("Pattern" + p.getPathVariable().map(pv -> " path=" + pv).orElse(""));
        depth++;
        printPatternElement(p.getElement());
        depth--;
    }

    private void printPatternElement(PatternElement element) {
        List<NodePattern> nodes = element.getNodes();
        List<RelationshipPattern> rels = element.getRelationships();
        printNode(nodes.get(0));
        for (int i = 0; i < rels.size(); i++) {
            printRelationship(rels.get(i));
            printNode(nodes.get(i + 1));
        }
    }

    private void printNode(NodePattern n) {
        StringBuilder sb = new StringBuilder("Node");
        n.getVariable().ifPresent(v -> sb.append(" var=").append(v));
        if (!n.getLabels().isEmpty()) {
            sb.append(" labels=").append(n.getLabels());
        }
        line(sb.toString());
        n.getProperties().ifPresent(this::printProperties);
    }

    private void printRelationship(RelationshipPattern r) {
        StringBuilder sb = new StringBuilder("Relationship dir=").append(r.getDirection());
        r.getVariable().ifPresent(v -> sb.append(" var=").append(v));
        if (!r.getTypes().isEmpty()) {
            sb.append(" types=").append(r.getTypes());
        }
        if (r.isVariableLength()) {
            sb.append(" length=[").append(r.getLower().isPresent() ? r.getLower().getAsInt() : "").append("..")
                            .append(r.getUpper().isPresent() ? r.getUpper().getAsInt() : "").append("]");
        }
        line(sb.toString());
        r.getProperties().ifPresent(this::printProperties);
    }

    private void printProperties(PropertiesExpression props) {
        depth++;
        line("Properties");
        depth++;
        printExpression(props.getExpression());
        depth--;
        depth--;
    }

    private void printExpression(Expression expr) {
        if (expr instanceof VariableExpression) {
            line("Var " + ((VariableExpression) expr).getName());
            return;
        }
        if (expr instanceof PropertyExpression) {
            PropertyExpression pe = (PropertyExpression) expr;
            line("Property path=" + pe.getPropertyPath());
            depth++;
            printExpression(pe.getTarget());
            depth--;
            return;
        }
        if (expr instanceof LabelCheckExpression) {
            LabelCheckExpression lc = (LabelCheckExpression) expr;
            line("LabelCheck :" + lc.getLabel());
            depth++;
            printExpression(lc.getTarget());
            depth--;
            return;
        }
        if (expr instanceof BinaryExpression) {
            BinaryExpression b = (BinaryExpression) expr;
            line("Binary " + b.getOperator().getSymbol());
            depth++;
            printExpression(b.getLeft());
            printExpression(b.getRight());
            depth--;
            return;
        }
        if (expr instanceof UnaryExpression) {
            UnaryExpression u = (UnaryExpression) expr;
            line("Unary " + u.getOperator().getSymbol());
            depth++;
            printExpression(u.getOperand());
            depth--;
            return;
        }
        if (expr instanceof FunctionCallExpression) {
            FunctionCallExpression f = (FunctionCallExpression) expr;
            line("Call " + f.getName() + (f.isDistinct() ? " DISTINCT" : "") + (f.isAggregate() ? " [aggregate]" : ""));
            depth++;
            for (Expression arg : f.getArguments()) {
                printExpression(arg);
            }
            depth--;
            return;
        }
        if (expr instanceof ParameterExpression) {
            line("Param $" + ((ParameterExpression) expr).getName());
            return;
        }
        if (expr instanceof LiteralExpression) {
            LiteralExpression lit = (LiteralExpression) expr;
            switch (lit.getKind()) {
                case LIST: {
                    line("List");
                    depth++;
                    for (Expression e : lit.asList()) {
                        printExpression(e);
                    }
                    depth--;
                    return;
                }
                case MAP: {
                    line("Map");
                    depth++;
                    for (Map.Entry<String,Expression> e : lit.asMap().entrySet()) {
                        line("Entry " + e.getKey());
                        depth++;
                        printExpression(e.getValue());
                        depth--;
                    }
                    depth--;
                    return;
                }
                case STRING:
                    line("Literal STRING '" + lit.getValue() + "'");
                    return;
                default:
                    line("Literal " + lit.getKind() + " " + lit.getValue());
                    return;
            }
        }
        line("Unknown " + expr.getClass().getSimpleName());
    }

    private void line(String text) {
        for (int i = 0; i < depth; i++) {
            out.append(INDENT);
        }
        out.append(text).append('\n');
    }
}
