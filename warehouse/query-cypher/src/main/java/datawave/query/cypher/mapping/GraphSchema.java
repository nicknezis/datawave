package datawave.query.cypher.mapping;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The Cypher-to-DataWave graph schema. Owns the alias translation between
 * Cypher labels/types and on-disk dataTypes, fields, and edge types.
 * <p>
 * Per feasibility plan decision #9, this is the single source of truth for
 * Cypher property → on-disk field translation; we do not pass through
 * {@code QueryModel}/{@code EdgeQueryModel} in the Cypher logic.
 * <p>
 * Hot reload (decision #5) is a M4 deliverable. M1 loads the schema once
 * at logic init and snapshots a {@link #getVersion()} that the planner
 * pins for the query's lifetime.
 */
public final class GraphSchema {

    private final long version;
    private final Map<String,NodeMapping> nodes;
    private final Map<String,RelMapping> relationships;

    public GraphSchema(long version, Collection<NodeMapping> nodes, Collection<RelMapping> relationships) {
        this.version = version;
        Map<String,NodeMapping> nodeMap = new LinkedHashMap<>();
        for (NodeMapping n : nodes) {
            if (nodeMap.put(n.getLabel(), n) != null) {
                throw new IllegalArgumentException("duplicate node label: " + n.getLabel());
            }
        }
        this.nodes = Collections.unmodifiableMap(nodeMap);

        Map<String,RelMapping> relMap = new LinkedHashMap<>();
        for (RelMapping r : relationships) {
            if (relMap.put(r.getCypherType(), r) != null) {
                throw new IllegalArgumentException("duplicate relationship type: " + r.getCypherType());
            }
        }
        this.relationships = Collections.unmodifiableMap(relMap);

        validate();
    }

    public long getVersion() {
        return version;
    }

    public Optional<NodeMapping> getNode(String label) {
        return Optional.ofNullable(nodes.get(Objects.requireNonNull(label, "label")));
    }

    public Optional<RelMapping> getRelationship(String cypherType) {
        return Optional.ofNullable(relationships.get(Objects.requireNonNull(cypherType, "cypherType")));
    }

    public Map<String,NodeMapping> getNodes() {
        return nodes;
    }

    public Map<String,RelMapping> getRelationships() {
        return relationships;
    }

    public NodeMapping requireNode(String label) {
        return getNode(label).orElseThrow(() -> new IllegalArgumentException("Cypher label not in graph schema: " + label));
    }

    public RelMapping requireRelationship(String cypherType) {
        return getRelationship(cypherType).orElseThrow(() -> new IllegalArgumentException("Cypher relationship type not in graph schema: " + cypherType));
    }

    private void validate() {
        for (RelMapping r : relationships.values()) {
            if (!nodes.containsKey(r.getSourceLabel())) {
                throw new IllegalArgumentException("relationship " + r.getCypherType() + " references unknown sourceLabel " + r.getSourceLabel());
            }
            if (!nodes.containsKey(r.getSinkLabel())) {
                throw new IllegalArgumentException("relationship " + r.getCypherType() + " references unknown sinkLabel " + r.getSinkLabel());
            }
        }
    }

    /** Convenience for tests / programmatic construction. */
    public static GraphSchema of(List<NodeMapping> nodes, List<RelMapping> relationships) {
        return new GraphSchema(0L, nodes, relationships);
    }
}
