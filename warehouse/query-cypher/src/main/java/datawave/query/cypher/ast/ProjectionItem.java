package datawave.query.cypher.ast;

import java.util.Optional;

public final class ProjectionItem extends AstNode {

    private final Expression expression;
    private final String alias;

    public ProjectionItem(SourceLocation location, Expression expression, String alias) {
        super(location);
        this.expression = expression;
        this.alias = alias;
    }

    public Expression getExpression() {
        return expression;
    }

    public Optional<String> getAlias() {
        return Optional.ofNullable(alias);
    }

    /**
     * The name under which this projection is exposed to downstream clauses.
     * If an explicit {@code AS alias} was given, that is the name. Otherwise,
     * simple references get an implicit name matching their source text —
     * {@code x} for a variable, {@code x.prop} for a property access,
     * {@code $p} for a parameter. Complex expressions (arithmetic, function
     * calls, comparisons) have no implicit name and must be aliased with
     * {@code AS} to be referenced downstream.
     */
    public Optional<String> getExposedName() {
        if (alias != null) {
            return Optional.of(alias);
        }
        return implicitName(expression);
    }

    private static Optional<String> implicitName(Expression expr) {
        if (expr instanceof VariableExpression) {
            return Optional.of(((VariableExpression) expr).getName());
        }
        if (expr instanceof PropertyExpression) {
            PropertyExpression pe = (PropertyExpression) expr;
            Optional<String> base = implicitName(pe.getTarget());
            if (!base.isPresent()) {
                return Optional.empty();
            }
            StringBuilder sb = new StringBuilder(base.get());
            for (String segment : pe.getPropertyPath()) {
                sb.append('.').append(segment);
            }
            return Optional.of(sb.toString());
        }
        if (expr instanceof ParameterExpression) {
            return Optional.of("$" + ((ParameterExpression) expr).getName());
        }
        return Optional.empty();
    }
}
