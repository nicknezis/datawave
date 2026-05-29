package datawave.query.cypher.ast;

/**
 * The inline property-filter expression that follows a node or relationship head, e.g. {@code {name: 'X', year: 2024}}. Only map literals and parameter
 * references are legal positions.
 */
public final class PropertiesExpression extends AstNode {

    private final Expression expression;

    public PropertiesExpression(SourceLocation location, Expression expression) {
        super(location);
        this.expression = expression;
    }

    public Expression getExpression() {
        return expression;
    }
}
