package datawave.query.cypher.ast;

/**
 * A parameter reference, either named ({@code $name}) or indexed ({@code $0}).
 */
public final class ParameterExpression extends Expression {

    private final String name;

    public ParameterExpression(SourceLocation location, String name) {
        super(location);
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
