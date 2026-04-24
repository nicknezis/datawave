package datawave.query.cypher.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

/**
 * Scope resolver + binding-table builder for the M0 read-only Cypher subset.
 *
 * Rules enforced:
 *   - Every variable referenced in WHERE / projections / ORDER BY must be
 *     bound by an earlier MATCH in the same scope, or carried forward by a
 *     WITH projection.
 *   - A variable reused across pattern positions must keep its kind
 *     (NODE vs RELATIONSHIP vs PATH). Label / type sets are unioned across
 *     occurrences rather than intersected, matching Cypher's "additional
 *     label assertion" semantics.
 *   - Variable-length relationships {@code [*lo..hi]} must have lo >= 0 and
 *     hi >= lo when both are present. Unbounded forms ({@code [*]} or
 *     missing upper) are rejected as out of scope for v1.
 *   - Aggregating functions (COUNT/SUM/AVG/MIN/MAX/COLLECT) are forbidden
 *     in WHERE expressions but allowed in projection positions.
 *   - UNION branches must all RETURN, and is itself rejected as out of
 *     scope for M0 since no downstream planner consumes it yet.
 *
 * Intentionally NOT enforced in M0 (deferred to M1+):
 *   - Type inference over projection expressions.
 *   - Alignment of UNION branches' return columns (UNION is rejected).
 *   - Path-binding constraint propagation into RETURN shape.
 */
public final class SemanticAnalyzer {

    private final List<SemanticException.Issue> issues = new ArrayList<>();

    public BindingTable analyze(CypherQuery query) {
        issues.clear();
        if (query.getBranches().size() > 1) {
            issues.add(new SemanticException.Issue(query.getLocation(), "UNION is not supported in the M0 Cypher subset"));
        }
        SingleQuery single = query.getBranches().get(0);
        BindingTable result = analyzeSingleQuery(single);
        if (!issues.isEmpty()) {
            throw new SemanticException(issues);
        }
        return result;
    }

    private BindingTable analyzeSingleQuery(SingleQuery query) {
        List<Scope> scopes = new ArrayList<>();
        Scope current = new Scope();
        scopes.add(current);

        for (ReadingClause rc : query.getReadingClauses()) {
            if (rc instanceof MatchClause) {
                analyzeMatch(current, (MatchClause) rc);
            } else if (rc instanceof WithClause) {
                current = analyzeWith(current, (WithClause) rc);
                scopes.add(current);
            }
        }

        Scope returnScope = null;
        Optional<ReturnClause> ret = query.getReturnClause();
        if (ret.isPresent()) {
            returnScope = analyzeReturn(current, ret.get());
        }
        return new BindingTable(scopes, returnScope);
    }

    // ---------- MATCH ---------------------------------------------------

    private void analyzeMatch(Scope scope, MatchClause match) {
        for (Pattern p : match.getPatterns()) {
            p.getPathVariable().ifPresent(path -> bindOrCheck(scope, path, BoundType.PATH, Collections.emptyList(), p.getLocation()));
            analyzePatternElement(scope, p.getElement());
        }
        match.getWhere().ifPresent(w -> analyzeExpression(scope, w, ExpressionContext.WHERE));
    }

    private void analyzePatternElement(Scope scope, PatternElement element) {
        for (NodePattern n : element.getNodes()) {
            n.getVariable().ifPresent(v -> bindOrCheck(scope, v, BoundType.NODE, n.getLabels(), n.getLocation()));
            n.getProperties().ifPresent(props -> validatePropertiesLiteral(scope, props));
        }
        for (RelationshipPattern r : element.getRelationships()) {
            if (r.isVariableLength() && !r.getUpper().isPresent()) {
                issues.add(new SemanticException.Issue(r.getLocation(),
                                "unbounded variable-length relationship [*] is not supported; provide an upper bound, e.g. [*1..3]"));
            }
            if (r.getLower().isPresent() && r.getUpper().isPresent() && r.getLower().getAsInt() > r.getUpper().getAsInt()) {
                issues.add(new SemanticException.Issue(r.getLocation(),
                                "variable-length lower bound " + r.getLower().getAsInt() + " exceeds upper bound " + r.getUpper().getAsInt()));
            }
            if (r.isVariableLength() && r.getVariable().isPresent()) {
                // Variable-length relationships bind a list of relationships. v1 accepts this as a VALUE binding
                // because the planner treats it as an opaque collection until M3 adds path-variable introspection.
                bindOrCheck(scope, r.getVariable().get(), BoundType.VALUE, r.getTypes(), r.getLocation());
            } else {
                r.getVariable().ifPresent(v -> bindOrCheck(scope, v, BoundType.RELATIONSHIP, r.getTypes(), r.getLocation()));
            }
            r.getProperties().ifPresent(props -> validatePropertiesLiteral(scope, props));
        }
    }

    private void validatePropertiesLiteral(Scope scope, PropertiesExpression props) {
        Expression expr = props.getExpression();
        if (expr instanceof LiteralExpression) {
            LiteralExpression lit = (LiteralExpression) expr;
            if (lit.getKind() == LiteralExpression.Kind.MAP) {
                for (Expression v : lit.asMap().values()) {
                    analyzeExpression(scope, v, ExpressionContext.PATTERN_PROPERTY);
                }
                return;
            }
        }
        if (expr instanceof ParameterExpression) {
            return;
        }
        issues.add(new SemanticException.Issue(props.getLocation(), "inline properties must be a map literal or parameter"));
    }

    private void bindOrCheck(Scope scope, String name, BoundType type, List<String> labelsOrTypes, SourceLocation location) {
        Optional<Binding> existing = scope.lookup(name);
        if (!existing.isPresent()) {
            scope.put(new Binding(name, type, location, new ArrayList<>(labelsOrTypes)));
            return;
        }
        Binding prev = existing.get();
        if (prev.getType() != type) {
            issues.add(new SemanticException.Issue(location, "variable '" + name + "' is already bound as " + prev.getType() + " at " + prev.getFirstSeen()
                            + "; cannot rebind as " + type));
            return;
        }
        if (!labelsOrTypes.isEmpty()) {
            // Union the label sets across occurrences (Cypher's "additional label assertion").
            Set<String> merged = new LinkedHashSet<>(prev.getLabelsOrTypes());
            merged.addAll(labelsOrTypes);
            scope.put(new Binding(name, type, prev.getFirstSeen(), new ArrayList<>(merged)));
        }
    }

    // ---------- WITH ----------------------------------------------------

    private Scope analyzeWith(Scope previous, WithClause with) {
        Scope next = new Scope();
        analyzeProjections(previous, next, with.getProjections(), with.isProjectAll(), with.getLocation());
        for (SortItem s : with.getOrderBy()) {
            analyzeExpression(next, s.getExpression(), ExpressionContext.ORDER_BY);
        }
        with.getSkip().ifPresent(e -> analyzeInteger(next, e, "SKIP"));
        with.getLimit().ifPresent(e -> analyzeInteger(next, e, "LIMIT"));
        with.getWhere().ifPresent(w -> analyzeExpression(next, w, ExpressionContext.WHERE));
        return next;
    }

    // ---------- RETURN --------------------------------------------------

    private Scope analyzeReturn(Scope previous, ReturnClause ret) {
        Scope exposed = new Scope();
        analyzeProjections(previous, exposed, ret.getProjections(), ret.isProjectAll(), ret.getLocation());
        for (SortItem s : ret.getOrderBy()) {
            analyzeExpression(exposed, s.getExpression(), ExpressionContext.ORDER_BY);
        }
        ret.getSkip().ifPresent(e -> analyzeInteger(exposed, e, "SKIP"));
        ret.getLimit().ifPresent(e -> analyzeInteger(exposed, e, "LIMIT"));
        return exposed;
    }

    private void analyzeProjections(Scope source, Scope target, List<ProjectionItem> items, boolean projectAll, SourceLocation clauseLocation) {
        if (projectAll) {
            // WITH * / RETURN *: carry every variable from the previous scope forward.
            for (Binding b : source.bindings()) {
                target.put(b);
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        for (ProjectionItem item : items) {
            analyzeExpression(source, item.getExpression(), ExpressionContext.PROJECTION);
            Optional<String> exposed = item.getExposedName();
            if (!exposed.isPresent()) {
                issues.add(new SemanticException.Issue(item.getLocation(),
                                "projection expression has no implicit name; add an alias with 'AS <name>'"));
                continue;
            }
            String name = exposed.get();
            if (!seen.add(name)) {
                issues.add(new SemanticException.Issue(item.getLocation(), "duplicate projection name '" + name + "' in " + contextOf(clauseLocation)));
                continue;
            }
            BoundType type = inferProjectedType(source, item.getExpression());
            target.put(new Binding(name, type, item.getLocation(), Collections.emptyList()));
        }
    }

    private String contextOf(SourceLocation location) {
        return "clause at " + location;
    }

    private BoundType inferProjectedType(Scope source, Expression expression) {
        if (expression instanceof VariableExpression) {
            String n = ((VariableExpression) expression).getName();
            return source.lookup(n).map(Binding::getType).orElse(BoundType.VALUE);
        }
        return BoundType.VALUE;
    }

    // ---------- Expressions --------------------------------------------

    private enum ExpressionContext {
        WHERE,
        PROJECTION,
        ORDER_BY,
        PATTERN_PROPERTY
    }

    private void analyzeExpression(Scope scope, Expression expr, ExpressionContext context) {
        if (expr instanceof VariableExpression) {
            String name = ((VariableExpression) expr).getName();
            if (!scope.contains(name)) {
                issues.add(new SemanticException.Issue(expr.getLocation(), "variable '" + name + "' is not defined in scope"));
            }
            return;
        }
        if (expr instanceof PropertyExpression) {
            analyzeExpression(scope, ((PropertyExpression) expr).getTarget(), context);
            return;
        }
        if (expr instanceof LabelCheckExpression) {
            analyzeExpression(scope, ((LabelCheckExpression) expr).getTarget(), context);
            return;
        }
        if (expr instanceof BinaryExpression) {
            BinaryExpression b = (BinaryExpression) expr;
            analyzeExpression(scope, b.getLeft(), context);
            analyzeExpression(scope, b.getRight(), context);
            return;
        }
        if (expr instanceof UnaryExpression) {
            analyzeExpression(scope, ((UnaryExpression) expr).getOperand(), context);
            return;
        }
        if (expr instanceof FunctionCallExpression) {
            FunctionCallExpression fn = (FunctionCallExpression) expr;
            if (fn.isAggregate() && context == ExpressionContext.WHERE) {
                issues.add(new SemanticException.Issue(fn.getLocation(), "aggregating function '" + fn.getName() + "' is not allowed inside WHERE"));
            }
            if (fn.isAggregate() && context == ExpressionContext.PATTERN_PROPERTY) {
                issues.add(new SemanticException.Issue(fn.getLocation(),
                                "aggregating function '" + fn.getName() + "' is not allowed inside an inline property filter"));
            }
            for (Expression arg : fn.getArguments()) {
                analyzeExpression(scope, arg, context);
            }
            return;
        }
        if (expr instanceof LiteralExpression) {
            LiteralExpression lit = (LiteralExpression) expr;
            if (lit.getKind() == LiteralExpression.Kind.LIST) {
                for (Expression e : lit.asList()) {
                    analyzeExpression(scope, e, context);
                }
            } else if (lit.getKind() == LiteralExpression.Kind.MAP) {
                for (Expression e : lit.asMap().values()) {
                    analyzeExpression(scope, e, context);
                }
            }
            return;
        }
        if (expr instanceof ParameterExpression) {
            return;
        }
    }

    private void analyzeInteger(Scope scope, Expression expr, String clauseName) {
        analyzeExpression(scope, expr, ExpressionContext.PROJECTION);
        if (expr instanceof LiteralExpression) {
            LiteralExpression lit = (LiteralExpression) expr;
            if (lit.getKind() != LiteralExpression.Kind.INTEGER) {
                issues.add(new SemanticException.Issue(expr.getLocation(), clauseName + " must be an integer literal or parameter"));
            } else if (((long) lit.getValue()) < 0L) {
                issues.add(new SemanticException.Issue(expr.getLocation(), clauseName + " must be non-negative"));
            }
            return;
        }
        if (expr instanceof ParameterExpression) {
            return;
        }
        issues.add(new SemanticException.Issue(expr.getLocation(), clauseName + " must be an integer literal or parameter"));
    }
}
