package datawave.query.cypher.ast;

public final class UnaryExpression extends Expression {

    public enum Operator {
        NEGATE("-"),
        POSITIVE("+"),
        NOT("NOT");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        public String getSymbol() {
            return symbol;
        }
    }

    private final Operator operator;
    private final Expression operand;

    public UnaryExpression(SourceLocation location, Operator operator, Expression operand) {
        super(location);
        this.operator = operator;
        this.operand = operand;
    }

    public Operator getOperator() {
        return operator;
    }

    public Expression getOperand() {
        return operand;
    }
}
