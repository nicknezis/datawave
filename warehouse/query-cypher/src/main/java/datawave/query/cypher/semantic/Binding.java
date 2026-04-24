package datawave.query.cypher.semantic;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import datawave.query.cypher.ast.SourceLocation;

/**
 * A single variable binding in a {@link Scope}. Immutable; scopes create new
 * bindings rather than mutating existing ones.
 */
public final class Binding {

    private final String name;
    private final BoundType type;
    private final SourceLocation firstSeen;
    private final List<String> labelsOrTypes;

    public Binding(String name, BoundType type, SourceLocation firstSeen, List<String> labelsOrTypes) {
        this.name = Objects.requireNonNull(name);
        this.type = Objects.requireNonNull(type);
        this.firstSeen = firstSeen == null ? SourceLocation.UNKNOWN : firstSeen;
        this.labelsOrTypes = Collections.unmodifiableList(labelsOrTypes);
    }

    public String getName() {
        return name;
    }

    public BoundType getType() {
        return type;
    }

    public SourceLocation getFirstSeen() {
        return firstSeen;
    }

    /**
     * For NODE bindings, the labels asserted for the variable (intersection
     * of all pattern occurrences). For RELATIONSHIP bindings, the declared
     * relationship types. Empty for PATH / VALUE bindings.
     */
    public List<String> getLabelsOrTypes() {
        return labelsOrTypes;
    }
}
