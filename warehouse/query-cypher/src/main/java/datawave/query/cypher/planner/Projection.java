package datawave.query.cypher.planner;

import java.util.Objects;

/**
 * One column in the RETURN clause, resolved to its bound variable, the
 * property name, and the exposed alias.
 *
 * <p>{@link Kind} distinguishes how the value is sourced:
 * <ul>
 *   <li>{@link Kind#NODE_PROPERTY} — the node identity property, read
 *       directly from the edge row SOURCE/SINK; no shard lookup needed.</li>
 *   <li>{@link Kind#NODE_SHARD_PROPERTY} — a non-identity node property
 *       that requires a shard-table lookup via ShardEnrichmentService.</li>
 *   <li>{@link Kind#REL_PROPERTY} — an edge attribute slot value, read
 *       from the edge column qualifier.</li>
 * </ul>
 */
public final class Projection {

    public enum Kind {
        /** Node identity property (edge SOURCE/SINK). No shard lookup needed. */
        NODE_PROPERTY,
        /** Non-identity node property requiring shard enrichment. */
        NODE_SHARD_PROPERTY,
        /** Edge attribute slot value from the edge column qualifier. */
        REL_PROPERTY
    }

    private final String alias;
    private final String variable;
    private final String property;
    private final Kind kind;

    public Projection(String alias, String variable, String property, Kind kind) {
        this.alias = Objects.requireNonNull(alias, "alias");
        this.variable = Objects.requireNonNull(variable, "variable");
        this.property = Objects.requireNonNull(property, "property");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public String getAlias() {
        return alias;
    }

    public String getVariable() {
        return variable;
    }

    public String getProperty() {
        return property;
    }

    public Kind getKind() {
        return kind;
    }
}
