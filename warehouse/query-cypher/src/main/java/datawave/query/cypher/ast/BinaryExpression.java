package datawave.query.cypher.ast;

public final class BinaryExpression extends Expression {

    public enum Operator {
        OR("OR"),
        AND("AND"),
        EQ("="),
        NEQ("<>"),
        LT("<"),
        LTE("<="),
        GT(">"),
        GTE(">="),
        ADD("+"),
        SUB("-"),
        MUL("*"),
        DIV("/"),
        MOD("%");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        public String getSymbol() {
            return symbol;
        }
    }

    private final Operator operator;
    private final Expression left;
    private final Expression right;

    public BinaryExpression(SourceLocation location, Operator operator, Expression left, Expression right) {
        super(location);
        this.operator = operator;
        this.left = left;
        this.right = right;
    }

    public Operator getOperator() {
        return operator;
    }

    public Expression getLeft() {
        return left;
    }

    public Expression getRight() {
        return right;
    }
}
