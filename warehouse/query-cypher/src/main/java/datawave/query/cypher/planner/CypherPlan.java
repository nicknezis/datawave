package datawave.query.cypher.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The M2 logical plan: an ordered list of {@link HopSpec hops} (one per
 * relationship in the query), the RETURN projection list, an optional LIMIT,
 * and the ORDER BY / SKIP / DISTINCT post-processing directives.
 *
 * <p>Single-hop queries produce a plan with exactly one hop; the
 * {@link #getFirstHop()} convenience covers that common case.
 */
public final class CypherPlan {

    private final List<HopSpec> hops;
    private final List<Projection> projections;
    private final Long limit;
    private final Long skip;
    private final boolean distinct;
    private final List<SortSpec> orderBy;
    private final long schemaVersion;

    public CypherPlan(List<HopSpec> hops, List<Projection> projections, Long limit, Long skip,
                    boolean distinct, List<SortSpec> orderBy, long schemaVersion) {
        if (Objects.requireNonNull(hops, "hops").isEmpty()) {
            throw new IllegalArgumentException("plan must have at least one hop");
        }
        this.hops = Collections.unmodifiableList(new ArrayList<>(hops));
        this.projections = Collections.unmodifiableList(new ArrayList<>(projections));
        this.limit = limit;
        this.skip = skip;
        this.distinct = distinct;
        this.orderBy = Collections.unmodifiableList(new ArrayList<>(orderBy));
        this.schemaVersion = schemaVersion;
    }

    /** All hops in traversal order (hop 0 is executed first). */
    public List<HopSpec> getHops() {
        return hops;
    }

    /** The first (and for single-hop queries the only) hop. */
    public HopSpec getFirstHop() {
        return hops.get(0);
    }

    /**
     * @deprecated use {@link #getFirstHop()} or {@link #getHops()}
     */
    @Deprecated
    public HopSpec getHop() {
        return getFirstHop();
    }

    public List<Projection> getProjections() {
        return projections;
    }

    public OptionalLong getLimit() {
        return limit == null ? OptionalLong.empty() : OptionalLong.of(limit);
    }

    public Optional<Long> getLimitBoxed() {
        return Optional.ofNullable(limit);
    }

    public Optional<Long> getSkipBoxed() {
        return Optional.ofNullable(skip);
    }

    public boolean isDistinct() {
        return distinct;
    }

    public List<SortSpec> getOrderBy() {
        return orderBy;
    }

    public long getSchemaVersion() {
        return schemaVersion;
    }
}
