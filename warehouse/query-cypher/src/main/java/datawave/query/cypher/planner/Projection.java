package datawave.query.cypher.planner;

import java.util.Objects;
import java.util.Optional;

/**
 * One column in the RETURN clause, resolved to its bound variable, the property name, and the exposed alias.
 *
 * <p>
 * {@link Kind} distinguishes how the value is sourced:
 * <ul>
 * <li>{@link Kind#NODE_PROPERTY} — the node identity property, read directly from the edge row SOURCE/SINK; no shard lookup needed.</li>
 * <li>{@link Kind#NODE_SHARD_PROPERTY} — a non-identity node property that requires a shard-table lookup via ShardEnrichmentService.</li>
 * <li>{@link Kind#REL_PROPERTY} — an edge attribute slot value, read from the edge column qualifier.</li>
 * <li>{@link Kind#PATH_OBJECT} — the geometry bound to a Cypher path variable (e.g. {@code MATCH p = (a)-[*1..n]-(b) RETURN p}); the transformer materialises
 * it as a {@link datawave.query.cypher.transformer.CypherValue.PathValue}. {@link #getProperty()} is {@code null} for this kind.</li>
 * <li>{@link Kind#AGGREGATE} — an aggregating projection. The streaming aggregator stores the per-group result under {@code __agg__.<alias>} in the emitted
 * {@link datawave.query.cypher.executor.PathTuple}. {@link #getAggregateSpec()} carries the function and argument.</li>
 * </ul>
 */
public final class Projection {

    /** Reserved tuple-key prefix the executor uses to stash aggregate values. */
    public static final String AGGREGATE_TUPLE_KEY_PREFIX = "__agg__.";

    public enum Kind {
        /** Node identity property (edge SOURCE/SINK). No shard lookup needed. */
        NODE_PROPERTY,
        /** Non-identity node property requiring shard enrichment. */
        NODE_SHARD_PROPERTY,
        /** Edge attribute slot value from the edge column qualifier. */
        REL_PROPERTY,
        /** Whole-path object bound to a Cypher path variable. */
        PATH_OBJECT,
        /** Aggregating projection (COUNT/SUM/AVG/MIN/MAX). */
        AGGREGATE
    }

    private final String alias;
    private final String variable;
    private final String property;
    private final Kind kind;
    private final AggregateSpec aggregateSpec;

    /** Constructor for value-bearing projections (NODE_PROPERTY, NODE_SHARD_PROPERTY, REL_PROPERTY). */
    public Projection(String alias, String variable, String property, Kind kind) {
        this(alias, variable, property, kind, null);
        if (kind == Kind.PATH_OBJECT || kind == Kind.AGGREGATE) {
            throw new IllegalArgumentException("use the dedicated factory for " + kind);
        }
        Objects.requireNonNull(property, "property");
    }

    private Projection(String alias, String variable, String property, Kind kind, AggregateSpec aggregateSpec) {
        this.alias = Objects.requireNonNull(alias, "alias");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.variable = variable;
        this.property = property;
        this.aggregateSpec = aggregateSpec;
    }

    /**
     * Build a {@link Kind#PATH_OBJECT} projection bound to a path variable.
     */
    public static Projection pathObject(String alias, String pathVariable) {
        Objects.requireNonNull(pathVariable, "pathVariable");
        return new Projection(alias, pathVariable, null, Kind.PATH_OBJECT, null);
    }

    /**
     * Build a {@link Kind#AGGREGATE} projection driven by the supplied {@link AggregateSpec}. {@code variable}/{@code property} are derived from the spec's
     * argument (or left null for COUNT_STAR) so the executor can find the per-row argument value without a separate lookup.
     */
    public static Projection aggregate(String alias, AggregateSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return new Projection(alias, spec.getArgumentVariable().orElse(null), spec.getArgumentProperty().orElse(null), Kind.AGGREGATE, spec);
    }

    public String getAlias() {
        return alias;
    }

    /**
     * Variable name. {@code null} for {@link Kind#AGGREGATE} of {@code COUNT(*)}; the variable bound by the path for {@link Kind#PATH_OBJECT}; otherwise the
     * source variable for the property lookup.
     */
    public String getVariable() {
        return variable;
    }

    /** {@code null} for {@link Kind#PATH_OBJECT} and {@link Kind#AGGREGATE} of {@code COUNT(*)}. */
    public String getProperty() {
        return property;
    }

    public Kind getKind() {
        return kind;
    }

    public Optional<AggregateSpec> getAggregateSpec() {
        return Optional.ofNullable(aggregateSpec);
    }
}
