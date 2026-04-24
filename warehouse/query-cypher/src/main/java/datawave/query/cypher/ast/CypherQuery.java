package datawave.query.cypher.ast;

import java.util.Collections;
import java.util.List;

/**
 * Top-level parsed Cypher query. M0 scope is a single {@link SingleQuery};
 * UNION support is represented as a non-empty list so the grammar's
 * regularQuery production round-trips, but the semantic analyzer only checks
 * the first branch until UNION is promoted out of M0.
 */
public final class CypherQuery extends AstNode {

    private final List<SingleQuery> branches;
    private final List<Boolean> unionAll;

    public CypherQuery(SourceLocation location, List<SingleQuery> branches, List<Boolean> unionAll) {
        super(location);
        this.branches = Collections.unmodifiableList(branches);
        this.unionAll = Collections.unmodifiableList(unionAll);
    }

    public List<SingleQuery> getBranches() {
        return branches;
    }

    /**
     * For a query with N branches, returns a list of length N-1 where element
     * i indicates whether the UNION joining branch i and branch i+1 was
     * {@code UNION ALL}.
     */
    public List<Boolean> getUnionAll() {
        return unionAll;
    }
}
