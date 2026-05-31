package datawave.query.cypher.mapping;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One Cypher relationship type's mapping onto an on-disk edge type. Carries:
 * <ul>
 * <li>the underlying TYPE column-family component,</li>
 * <li>direction (so undirected Cypher matches over an undirected edge can canonicalize endpoint pairs and avoid emitting two physical rows as two logical
 * results),</li>
 * <li>the labels expected at the source and sink endpoints (used for startup-time validation against the schema and for cross-checking the Cypher pattern's
 * node labels), and</li>
 * <li>which positional edge-attribute slot a Cypher relationship-property maps to.</li>
 * </ul>
 */
public final class RelMapping {

    private final String cypherType;
    private final String edgeType;
    private final String sourceLabel;
    private final String sinkLabel;
    private final EdgeDirection direction;
    private final Map<String,EdgeAttributeSlot> attributeMappings;

    public RelMapping(String cypherType, String edgeType, String sourceLabel, String sinkLabel, EdgeDirection direction,
                    Map<String,EdgeAttributeSlot> attributeMappings) {
        this.cypherType = Objects.requireNonNull(cypherType, "cypherType");
        this.edgeType = Objects.requireNonNull(edgeType, "edgeType");
        this.sourceLabel = Objects.requireNonNull(sourceLabel, "sourceLabel");
        this.sinkLabel = Objects.requireNonNull(sinkLabel, "sinkLabel");
        this.direction = Objects.requireNonNull(direction, "direction");
        this.attributeMappings = attributeMappings == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(attributeMappings));
    }

    public String getCypherType() {
        return cypherType;
    }

    public String getEdgeType() {
        return edgeType;
    }

    public String getSourceLabel() {
        return sourceLabel;
    }

    public String getSinkLabel() {
        return sinkLabel;
    }

    public EdgeDirection getDirection() {
        return direction;
    }

    public Map<String,EdgeAttributeSlot> getAttributeMappings() {
        return attributeMappings;
    }

    public Optional<EdgeAttributeSlot> resolveAttributeSlot(String propertyName) {
        return Optional.ofNullable(attributeMappings.get(propertyName));
    }
}
