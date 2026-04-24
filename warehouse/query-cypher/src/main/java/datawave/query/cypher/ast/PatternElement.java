package datawave.query.cypher.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a chain of {@code (node)-[rel]->(node)-[rel]->(node)...} tokens.
 * Normalized so {@code nodes} always has exactly one more entry than
 * {@code relationships}; for a single-node pattern the relationships list is
 * empty.
 */
public final class PatternElement extends AstNode {

    private final List<NodePattern> nodes;
    private final List<RelationshipPattern> relationships;

    public PatternElement(SourceLocation location, List<NodePattern> nodes, List<RelationshipPattern> relationships) {
        super(location);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("pattern must have at least one node");
        }
        if (relationships.size() != nodes.size() - 1) {
            throw new IllegalArgumentException("relationships.size must equal nodes.size - 1");
        }
        this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
        this.relationships = Collections.unmodifiableList(new ArrayList<>(relationships));
    }

    public List<NodePattern> getNodes() {
        return nodes;
    }

    public List<RelationshipPattern> getRelationships() {
        return relationships;
    }
}
