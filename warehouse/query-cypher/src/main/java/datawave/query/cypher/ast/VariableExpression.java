package datawave.query.cypher.ast;

public final class VariableExpression extends Expression {

    private final String name;

    public VariableExpression(SourceLocation location, String name) {
        super(location);
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
