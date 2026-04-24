package datawave.query.cypher.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The ordered list of scopes produced for a successfully-analyzed query.
 *
 * One entry per clause-level scope boundary:
 *   - slot 0: bindings visible after all MATCH clauses up to the first WITH
 *     (or the whole body if there's no WITH).
 *   - slot i (i>0): bindings visible after the i-th WITH, i.e. the
 *     projections (plus any subsequent MATCH bindings that reuse them).
 *
 * Intended consumer is the logical planner in M1, which needs to know which
 * variables each clause is allowed to reference. Exposed here so M0's tests
 * can assert analyzer correctness without a planner in place.
 */
public final class BindingTable {

    private final List<Scope> scopes;
    private final Scope returnScope;

    BindingTable(List<Scope> scopes, Scope returnScope) {
        this.scopes = Collections.unmodifiableList(new ArrayList<>(scopes));
        this.returnScope = returnScope;
    }

    public List<Scope> getScopes() {
        return scopes;
    }

    /**
     * Names visible to the RETURN clause (if the query has one). Distinct
     * from the last reading-scope when RETURN introduces new aliases via AS.
     */
    public Optional<Scope> getReturnScope() {
        return Optional.ofNullable(returnScope);
    }

    public Scope getCurrentScope() {
        return scopes.get(scopes.size() - 1);
    }
}
