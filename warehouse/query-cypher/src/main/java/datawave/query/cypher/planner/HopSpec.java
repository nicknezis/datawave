package datawave.query.cypher.planner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import datawave.query.cypher.ast.RelationshipPattern.Direction;
import datawave.query.cypher.mapping.RelMapping;

/**
 * A single planned edge hop: source/sink endpoints, the edge type, the
 * Cypher pattern direction (which combined with the schema's stored
 * {@link datawave.query.cypher.mapping.EdgeDirection} drives undirected
 * canonicalization), and any equality filters on edge attributes.
 */
public final class HopSpec {

    private final NodeBinding source;
    private final NodeBinding sink;
    private final RelMapping rel;
    private final Direction patternDirection;
    private final String relVariable;
    private final Map<String,String> attributeEquals;

    public HopSpec(NodeBinding source, NodeBinding sink, RelMapping rel, Direction patternDirection, String relVariable, Map<String,String> attributeEquals) {
        this.source = Objects.requireNonNull(source, "source");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.rel = Objects.requireNonNull(rel, "rel");
        this.patternDirection = Objects.requireNonNull(patternDirection, "patternDirection");
        this.relVariable = relVariable;
        this.attributeEquals = Collections.unmodifiableMap(new LinkedHashMap<>(attributeEquals));
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
}
