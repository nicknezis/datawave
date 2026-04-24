package datawave.query.cypher.ast;

/**
 * A post-fix label assertion: {@code x:Label}. Produces a boolean indicating
 * whether the bound entity carries the given label.
 */
public final class LabelCheckExpression extends Expression {

    private final Expression target;
    private final String label;

    public LabelCheckExpression(SourceLocation location, Expression target, String label) {
        super(location);
        this.target = target;
        this.label = label;
    }

    public Expression getTarget() {
        return target;
    }

    public String getLabel() {
        return label;
    }
}
