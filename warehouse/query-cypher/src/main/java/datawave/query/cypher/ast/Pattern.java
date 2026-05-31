package datawave.query.cypher.ast;

import java.util.Optional;

/**
 * A single named or anonymous path pattern. A named pattern ({@code p = (...)-[...]->(...)}) binds the whole path to the given variable; an anonymous pattern
 * binds only the sub-elements' variables.
 */
public final class Pattern extends AstNode {

    private final String pathVariable;
    private final PatternElement element;

    public Pattern(SourceLocation location, String pathVariable, PatternElement element) {
        super(location);
        this.pathVariable = pathVariable;
        this.element = element;
    }

    public Optional<String> getPathVariable() {
        return Optional.ofNullable(pathVariable);
    }

    public PatternElement getElement() {
        return element;
    }
}
