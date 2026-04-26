package datawave.query.cypher.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The M1 logical plan: exactly one {@link HopSpec} plus the RETURN
 * projection list and an optional LIMIT.
 *
 * Multi-hop plans, WITH chains, ORDER BY, DISTINCT, and aggregation are
 * deferred to later milestones; the planner rejects those cases up front.
 */
public final class CypherPlan {

    private final HopSpec hop;
    private final List<Projection> projections;
    private final Long limit;
    private final long schemaVersion;

    public CypherPlan(HopSpec hop, List<Projection> projections, Long limit, long schemaVersion) {
        this.hop = Objects.requireNonNull(hop, "hop");
        this.projections = Collections.unmodifiableList(new ArrayList<>(projections));
        this.limit = limit;
        this.schemaVersion = schemaVersion;
    }

    public HopSpec getHop() {
        return hop;
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

    public long getSchemaVersion() {
        return schemaVersion;
    }
}
