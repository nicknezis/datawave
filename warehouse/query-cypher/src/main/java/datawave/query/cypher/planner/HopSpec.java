package datawave.query.cypher.planner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import datawave.query.cypher.ast.RelationshipPattern.Direction;
import datawave.query.cypher.mapping.RelMapping;

/**
 * A single planned edge hop: source/sink endpoints, the edge type, the Cypher pattern direction (which combined with the schema's stored
 * {@link datawave.query.cypher.mapping.EdgeDirection} drives undirected canonicalization), and any equality filters on edge attributes.
 *
 * <p>
 * M3 additions: {@link #getLower}/{@link #getUpper} describe a variable-length expansion bound (both present iff {@link #isVariableLength}).
 * {@link #getPathVariable} carries the enclosing pattern's path-variable name so the executor can accumulate {@code MATCH p = ... RETURN p} geometry.
 */
public final class HopSpec {

    private final NodeBinding source;
    private final NodeBinding sink;
    private final RelMapping rel;
    private final Direction patternDirection;
    private final String relVariable;
    private final Map<String,String> attributeEquals;
    private final Integer lower;
    private final Integer upper;
    private final String pathVariable;

    /** M2 constructor — fixed-length, no path variable. */
    public HopSpec(NodeBinding source, NodeBinding sink, RelMapping rel, Direction patternDirection, String relVariable, Map<String,String> attributeEquals) {
        this(source, sink, rel, patternDirection, relVariable, attributeEquals, null, null, null);
    }

    /** M3 constructor — carries variable-length bounds and/or a path variable. */
    public HopSpec(NodeBinding source, NodeBinding sink, RelMapping rel, Direction patternDirection, String relVariable, Map<String,String> attributeEquals,
                    Integer lower, Integer upper, String pathVariable) {
        this.source = Objects.requireNonNull(source, "source");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.rel = Objects.requireNonNull(rel, "rel");
        this.patternDirection = Objects.requireNonNull(patternDirection, "patternDirection");
        this.relVariable = relVariable;
        this.attributeEquals = Collections.unmodifiableMap(new LinkedHashMap<>(attributeEquals));
        if ((lower == null) != (upper == null)) {
            throw new IllegalArgumentException("variable-length bounds must both be present or both absent");
        }
        this.lower = lower;
        this.upper = upper;
        this.pathVariable = pathVariable;
    }

    public NodeBinding getSource() {
        return source;
    }

    public NodeBinding getSink() {
        return sink;
    }

    public RelMapping getRel() {
        return rel;
    }

    public Direction getPatternDirection() {
        return patternDirection;
    }

    public Optional<String> getRelVariable() {
        return Optional.ofNullable(relVariable);
    }

    public Map<String,String> getAttributeEquals() {
        return attributeEquals;
    }

    public OptionalInt getLower() {
        return lower == null ? OptionalInt.empty() : OptionalInt.of(lower);
    }

    public OptionalInt getUpper() {
        return upper == null ? OptionalInt.empty() : OptionalInt.of(upper);
    }

    public boolean isVariableLength() {
        return lower != null;
    }

    public Optional<String> getPathVariable() {
        return Optional.ofNullable(pathVariable);
    }
}
