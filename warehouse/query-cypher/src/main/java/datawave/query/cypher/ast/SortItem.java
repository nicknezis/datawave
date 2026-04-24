package datawave.query.cypher.ast;

public final class SortItem extends AstNode {

    public enum Direction {
        ASC,
        DESC
    }

    private final Expression expression;
    private final Direction direction;

    public SortItem(SourceLocation location, Expression expression, Direction direction) {
        super(location);
        this.expression = expression;
        this.direction = direction;
    }

    public Expression getExpression() {
        return expression;
    }

    public Direction getDirection() {
        return direction;
    }
}
