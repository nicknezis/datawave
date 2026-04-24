package datawave.query.cypher.ast;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

public final class RelationshipPattern extends AstNode {

    public enum Direction {
        /** {@code (a)-[...]->(b)} */
        OUTGOING,
        /** {@code (a)<-[...]-(b)} */
        INCOMING,
        /** {@code (a)-[...]-(b)} or {@code (a)<-[...]->(b)} */
        UNDIRECTED
    }

    private final Direction direction;
    private final String variable;
    private final List<String> types;
    private final Integer lower;
    private final Integer upper;
    private final boolean variableLength;
    private final PropertiesExpression properties;

    public RelationshipPattern(SourceLocation location, Direction direction, String variable, List<String> types, boolean variableLength, Integer lower,
                    Integer upper, PropertiesExpression properties) {
        super(location);
        this.direction = direction;
        this.variable = variable;
        this.types = Collections.unmodifiableList(types);
        this.variableLength = variableLength;
        this.lower = lower;
        this.upper = upper;
        this.properties = properties;
    }

    public Direction getDirection() {
        return direction;
    }

    public Optional<String> getVariable() {
        return Optional.ofNullable(variable);
    }

    public List<String> getTypes() {
        return types;
    }

    public boolean isVariableLength() {
        return variableLength;
    }

    public OptionalInt getLower() {
        return lower == null ? OptionalInt.empty() : OptionalInt.of(lower);
    }

    public OptionalInt getUpper() {
        return upper == null ? OptionalInt.empty() : OptionalInt.of(upper);
    }

    public Optional<PropertiesExpression> getProperties() {
        return Optional.ofNullable(properties);
    }
}
