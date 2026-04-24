package datawave.query.cypher.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;

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
import datawave.query.cypher.ast.SourceLocation;
import datawave.query.cypher.ast.UnaryExpression;
import datawave.query.cypher.ast.VariableExpression;
import datawave.query.cypher.ast.WithClause;
import datawave.query.cypher.parser.antlr.CypherBaseVisitor;
import datawave.query.cypher.parser.antlr.CypherParser;

/**
 * Converts the ANTLR-generated parse tree into the typed AST defined under
 * {@link datawave.query.cypher.ast}. Keeps the parse tree isolated so the rest
 * of the planner never depends on ANTLR types.
 */
final class AstBuilder extends CypherBaseVisitor<Object> {

    CypherQuery buildQuery(CypherParser.CypherContext ctx) {
        CypherParser.RegularQueryContext rq = ctx.statement().regularQuery();
        List<SingleQuery> branches = new ArrayList<>();
        List<Boolean> unionAll = new ArrayList<>();
        branches.add(buildSingleQuery(rq.singleQuery()));
        for (CypherParser.UnionClauseContext uc : rq.unionClause()) {
            unionAll.add(uc.ALL() != null);
            branches.add(buildSingleQuery(uc.singleQuery()));
        }
        return new CypherQuery(loc(ctx.start), branches, unionAll);
    }

    private SingleQuery buildSingleQuery(CypherParser.SingleQueryContext ctx) {
        List<ReadingClause> reading = new ArrayList<>();
        for (CypherParser.ReadingClauseContext rc : ctx.readingClause()) {
            if (rc.matchClause() != null) {
                reading.add(buildMatch(rc.matchClause()));
            } else if (rc.withClause() != null) {
                reading.add(buildWith(rc.withClause()));
            }
        }
        ReturnClause returnClause = ctx.returnClause() != null ? buildReturn(ctx.returnClause()) : null;
        return new SingleQuery(loc(ctx.start), reading, returnClause);
    }

    // ---------- Clauses --------------------------------------------------

    private MatchClause buildMatch(CypherParser.MatchClauseContext ctx) {
        boolean optional = ctx.OPTIONAL() != null;
        List<Pattern> patterns = new ArrayList<>();
        for (CypherParser.PatternContext p : ctx.pattern()) {
            patterns.add(buildPattern(p));
        }
        Expression where = ctx.whereClause() != null ? buildExpression(ctx.whereClause().expression()) : null;
        return new MatchClause(loc(ctx.start), optional, patterns, where);
    }

    private WithClause buildWith(CypherParser.WithClauseContext ctx) {
        boolean distinct = ctx.DISTINCT() != null;
        List<ProjectionItem> projections = new ArrayList<>();
        boolean projectAll = ctx.projectionItems().STAR() != null;
        for (CypherParser.ProjectionItemContext p : ctx.projectionItems().projectionItem()) {
            projections.add(buildProjection(p));
        }
        List<SortItem> orderBy = buildSortItems(ctx.orderByClause());
        Expression skip = ctx.skipClause() != null ? buildExpression(ctx.skipClause().expression()) : null;
        Expression limit = ctx.limitClause() != null ? buildExpression(ctx.limitClause().expression()) : null;
        Expression where = ctx.whereClause() != null ? buildExpression(ctx.whereClause().expression()) : null;
        return new WithClause(loc(ctx.start), distinct, projectAll, projections, orderBy, skip, limit, where);
    }

    private ReturnClause buildReturn(CypherParser.ReturnClauseContext ctx) {
        boolean distinct = ctx.DISTINCT() != null;
        List<ProjectionItem> projections = new ArrayList<>();
        boolean projectAll = ctx.projectionItems().STAR() != null;
        for (CypherParser.ProjectionItemContext p : ctx.projectionItems().projectionItem()) {
            projections.add(buildProjection(p));
        }
        List<SortItem> orderBy = buildSortItems(ctx.orderByClause());
        Expression skip = ctx.skipClause() != null ? buildExpression(ctx.skipClause().expression()) : null;
        Expression limit = ctx.limitClause() != null ? buildExpression(ctx.limitClause().expression()) : null;
        return new ReturnClause(loc(ctx.start), distinct, projectAll, projections, orderBy, skip, limit);
    }

    private ProjectionItem buildProjection(CypherParser.ProjectionItemContext ctx) {
        Expression expr = buildExpression(ctx.expression());
        String alias = ctx.symbolicName() != null ? ctx.symbolicName().getText() : null;
        return new ProjectionItem(loc(ctx.start), expr, alias);
    }

    private List<SortItem> buildSortItems(CypherParser.OrderByClauseContext ctx) {
        List<SortItem> result = new ArrayList<>();
        if (ctx == null) {
            return result;
        }
        for (CypherParser.SortItemContext s : ctx.sortItem()) {
            Expression expr = buildExpression(s.expression());
            SortItem.Direction dir = SortItem.Direction.ASC;
            if (s.DESC() != null || s.DESCENDING() != null) {
                dir = SortItem.Direction.DESC;
            }
            result.add(new SortItem(loc(s.start), expr, dir));
        }
        return result;
    }

    // ---------- Patterns -------------------------------------------------

    private Pattern buildPattern(CypherParser.PatternContext ctx) {
        String pathVar = ctx.symbolicName() != null ? ctx.symbolicName().getText() : null;
        PatternElement element = buildPatternElement(ctx.patternElement());
        return new Pattern(loc(ctx.start), pathVar, element);
    }

    private PatternElement buildPatternElement(CypherParser.PatternElementContext ctx) {
        if (ctx.patternElement() != null) {
            return buildPatternElement(ctx.patternElement());
        }
        List<NodePattern> nodes = new ArrayList<>();
        List<RelationshipPattern> rels = new ArrayList<>();
        List<CypherParser.NodePatternContext> nodeCtxs = ctx.nodePattern();
        List<CypherParser.RelationshipPatternContext> relCtxs = ctx.relationshipPattern();
        nodes.add(buildNodePattern(nodeCtxs.get(0)));
        for (int i = 0; i < relCtxs.size(); i++) {
            rels.add(buildRelationshipPattern(relCtxs.get(i)));
            nodes.add(buildNodePattern(nodeCtxs.get(i + 1)));
        }
        return new PatternElement(loc(ctx.start), nodes, rels);
    }

    private NodePattern buildNodePattern(CypherParser.NodePatternContext ctx) {
        String var = ctx.symbolicName() != null ? ctx.symbolicName().getText() : null;
        List<String> labels = new ArrayList<>();
        if (ctx.nodeLabels() != null) {
            for (CypherParser.SymbolicNameContext s : ctx.nodeLabels().symbolicName()) {
                labels.add(s.getText());
            }
        }
        PropertiesExpression props = ctx.properties() != null ? buildProperties(ctx.properties()) : null;
        return new NodePattern(loc(ctx.start), var, labels, props);
    }

    private RelationshipPattern buildRelationshipPattern(CypherParser.RelationshipPatternContext ctx) {
        boolean hasLeft = ctx.leftArrow() != null;
        boolean hasRight = ctx.rightArrow() != null;
        RelationshipPattern.Direction direction;
        if (hasLeft && !hasRight) {
            direction = RelationshipPattern.Direction.INCOMING;
        } else if (!hasLeft && hasRight) {
            direction = RelationshipPattern.Direction.OUTGOING;
        } else {
            direction = RelationshipPattern.Direction.UNDIRECTED;
        }

        String var = null;
        List<String> types = new ArrayList<>();
        boolean variableLength = false;
        Integer lower = null;
        Integer upper = null;
        PropertiesExpression props = null;

        if (ctx.relationshipDetail() != null) {
            CypherParser.RelationshipDetailContext d = ctx.relationshipDetail();
            if (d.symbolicName() != null) {
                var = d.symbolicName().getText();
            }
            if (d.relationshipTypes() != null) {
                for (CypherParser.SymbolicNameContext s : d.relationshipTypes().symbolicName()) {
                    types.add(s.getText());
                }
            }
            if (d.rangeLiteral() != null) {
                // The grammar now requires both lower and upper bounds; see Cypher.g4.
                variableLength = true;
                lower = Integer.parseInt(d.rangeLiteral().lower.getText());
                upper = Integer.parseInt(d.rangeLiteral().upper.getText());
            }
            if (d.properties() != null) {
                props = buildProperties(d.properties());
            }
        }
        return new RelationshipPattern(loc(ctx.start), direction, var, types, variableLength, lower, upper, props);
    }

    private PropertiesExpression buildProperties(CypherParser.PropertiesContext ctx) {
        Expression expr;
        if (ctx.mapLiteral() != null) {
            expr = buildMapLiteral(ctx.mapLiteral());
        } else {
            expr = buildParameter(ctx.parameter());
        }
        return new PropertiesExpression(loc(ctx.start), expr);
    }

    // ---------- Expressions ---------------------------------------------

    private Expression buildExpression(CypherParser.ExpressionContext ctx) {
        return buildOr(ctx.orExpr());
    }

    private Expression buildOr(CypherParser.OrExprContext ctx) {
        Expression result = buildAnd(ctx.andExpr(0));
        for (int i = 1; i < ctx.andExpr().size(); i++) {
            result = new BinaryExpression(loc(ctx.start), BinaryExpression.Operator.OR, result, buildAnd(ctx.andExpr(i)));
        }
        return result;
    }

    private Expression buildAnd(CypherParser.AndExprContext ctx) {
        Expression result = buildNot(ctx.notExpr(0));
        for (int i = 1; i < ctx.notExpr().size(); i++) {
            result = new BinaryExpression(loc(ctx.start), BinaryExpression.Operator.AND, result, buildNot(ctx.notExpr(i)));
        }
        return result;
    }

    private Expression buildNot(CypherParser.NotExprContext ctx) {
        if (ctx.NOT() != null) {
            return new UnaryExpression(loc(ctx.start), UnaryExpression.Operator.NOT, buildNot(ctx.notExpr()));
        }
        return buildComparison(ctx.comparisonExpr());
    }

    private Expression buildComparison(CypherParser.ComparisonExprContext ctx) {
        Expression left = buildAdd(ctx.addExpr(0));
        if (ctx.comparisonOp() == null) {
            return left;
        }
        BinaryExpression.Operator op = comparisonOp(ctx.comparisonOp());
        Expression right = buildAdd(ctx.addExpr(1));
        return new BinaryExpression(loc(ctx.start), op, left, right);
    }

    private BinaryExpression.Operator comparisonOp(CypherParser.ComparisonOpContext ctx) {
        if (ctx.EQ() != null)
            return BinaryExpression.Operator.EQ;
        if (ctx.NEQ() != null)
            return BinaryExpression.Operator.NEQ;
        if (ctx.LTE() != null)
            return BinaryExpression.Operator.LTE;
        if (ctx.GTE() != null)
            return BinaryExpression.Operator.GTE;
        if (ctx.LT() != null)
            return BinaryExpression.Operator.LT;
        if (ctx.GT() != null)
            return BinaryExpression.Operator.GT;
        throw new IllegalStateException("unknown comparison operator: " + ctx.getText());
    }

    private Expression buildAdd(CypherParser.AddExprContext ctx) {
        // Grammar: mulExpr ((PLUS | MINUS) mulExpr)*; operator for mulExpr(i) lives at child index 2*i - 1.
        List<CypherParser.MulExprContext> operands = ctx.mulExpr();
        Expression result = buildMul(operands.get(0));
        for (int i = 1; i < operands.size(); i++) {
            TerminalNode opNode = (TerminalNode) ctx.getChild(2 * i - 1);
            BinaryExpression.Operator op = opNode.getSymbol().getType() == CypherParser.PLUS ? BinaryExpression.Operator.ADD : BinaryExpression.Operator.SUB;
            result = new BinaryExpression(loc(opNode.getSymbol()), op, result, buildMul(operands.get(i)));
        }
        return result;
    }

    private Expression buildMul(CypherParser.MulExprContext ctx) {
        // Grammar: unaryExpr ((STAR | SLASH | PERCENT) unaryExpr)*; operator for unaryExpr(i) lives at child index 2*i - 1.
        List<CypherParser.UnaryExprContext> operands = ctx.unaryExpr();
        Expression result = buildUnary(operands.get(0));
        for (int i = 1; i < operands.size(); i++) {
            TerminalNode opNode = (TerminalNode) ctx.getChild(2 * i - 1);
            BinaryExpression.Operator op;
            switch (opNode.getSymbol().getType()) {
                case CypherParser.STAR:
                    op = BinaryExpression.Operator.MUL;
                    break;
                case CypherParser.SLASH:
                    op = BinaryExpression.Operator.DIV;
                    break;
                case CypherParser.PERCENT:
                    op = BinaryExpression.Operator.MOD;
                    break;
                default:
                    throw new IllegalStateException("unexpected multiplicative operator: " + opNode.getText());
            }
            result = new BinaryExpression(loc(opNode.getSymbol()), op, result, buildUnary(operands.get(i)));
        }
        return result;
    }

    private Expression buildUnary(CypherParser.UnaryExprContext ctx) {
        if (ctx.MINUS() != null) {
            return new UnaryExpression(loc(ctx.start), UnaryExpression.Operator.NEGATE, buildUnary(ctx.unaryExpr()));
        }
        if (ctx.PLUS() != null) {
            return new UnaryExpression(loc(ctx.start), UnaryExpression.Operator.POSITIVE, buildUnary(ctx.unaryExpr()));
        }
        return buildPropertyOrLabel(ctx.propertyOrLabelExpr());
    }

    private Expression buildPropertyOrLabel(CypherParser.PropertyOrLabelExprContext ctx) {
        Expression result = buildAtom(ctx.atom());
        List<String> propertyPath = new ArrayList<>();
        for (CypherParser.PropertyLookupContext lookup : ctx.propertyLookup()) {
            propertyPath.add(lookup.symbolicName().getText());
        }
        if (!propertyPath.isEmpty()) {
            result = new PropertyExpression(loc(ctx.start), result, propertyPath);
        }
        if (ctx.symbolicName() != null) {
            result = new LabelCheckExpression(loc(ctx.start), result, ctx.symbolicName().getText());
        }
        return result;
    }

    private Expression buildAtom(CypherParser.AtomContext ctx) {
        if (ctx.literal() != null) {
            return buildLiteral(ctx.literal());
        }
        if (ctx.parameter() != null) {
            return buildParameter(ctx.parameter());
        }
        if (ctx.functionInvocation() != null) {
            return buildFunction(ctx.functionInvocation());
        }
        if (ctx.variable() != null) {
            return new VariableExpression(loc(ctx.start), ctx.variable().symbolicName().getText());
        }
        if (ctx.expression() != null) {
            return buildExpression(ctx.expression());
        }
        throw new IllegalStateException("unknown atom: " + ctx.getText());
    }

    private Expression buildFunction(CypherParser.FunctionInvocationContext ctx) {
        String name = ctx.symbolicName().getText();
        boolean distinct = ctx.DISTINCT() != null;
        List<Expression> args = new ArrayList<>();
        for (CypherParser.ExpressionContext e : ctx.expression()) {
            args.add(buildExpression(e));
        }
        return new FunctionCallExpression(loc(ctx.start), name, distinct, args);
    }

    private Expression buildParameter(CypherParser.ParameterContext ctx) {
        String name = ctx.symbolicName() != null ? ctx.symbolicName().getText() : ctx.INTEGER().getText();
        return new ParameterExpression(loc(ctx.start), name);
    }

    private Expression buildLiteral(CypherParser.LiteralContext ctx) {
        if (ctx.INTEGER() != null) {
            return LiteralExpression.ofInteger(loc(ctx.start), Long.parseLong(ctx.INTEGER().getText()));
        }
        if (ctx.DECIMAL() != null) {
            return LiteralExpression.ofDecimal(loc(ctx.start), Double.parseDouble(ctx.DECIMAL().getText()));
        }
        if (ctx.STRING() != null) {
            return LiteralExpression.ofString(loc(ctx.start), unquote(ctx.STRING().getText()));
        }
        if (ctx.TRUE() != null) {
            return LiteralExpression.ofBoolean(loc(ctx.start), true);
        }
        if (ctx.FALSE() != null) {
            return LiteralExpression.ofBoolean(loc(ctx.start), false);
        }
        if (ctx.NULL() != null) {
            return LiteralExpression.ofNull(loc(ctx.start));
        }
        if (ctx.listLiteral() != null) {
            List<Expression> items = new ArrayList<>();
            for (CypherParser.ExpressionContext e : ctx.listLiteral().expression()) {
                items.add(buildExpression(e));
            }
            return LiteralExpression.ofList(loc(ctx.start), items);
        }
        if (ctx.mapLiteral() != null) {
            return buildMapLiteral(ctx.mapLiteral());
        }
        throw new IllegalStateException("unknown literal: " + ctx.getText());
    }

    private LiteralExpression buildMapLiteral(CypherParser.MapLiteralContext ctx) {
        Map<String,Expression> entries = new LinkedHashMap<>();
        for (CypherParser.MapEntryContext entry : ctx.mapEntry()) {
            entries.put(entry.symbolicName().getText(), buildExpression(entry.expression()));
        }
        return LiteralExpression.ofMap(loc(ctx.start), entries);
    }

    // ---------- Helpers --------------------------------------------------

    private static SourceLocation loc(Token token) {
        if (token == null) {
            return SourceLocation.UNKNOWN;
        }
        return new SourceLocation(token.getLine(), token.getCharPositionInLine() + 1);
    }

    private static String unquote(String raw) {
        // Strip the surrounding quotes and unescape the common cases we care about.
        if (raw.length() < 2) {
            return raw;
        }
        String body = raw.substring(1, raw.length() - 1);
        StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char next = body.charAt(++i);
                switch (next) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '\'':
                        sb.append('\'');
                        break;
                    case '"':
                        sb.append('"');
                        break;
                    default:
                        sb.append(next);
                        break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
