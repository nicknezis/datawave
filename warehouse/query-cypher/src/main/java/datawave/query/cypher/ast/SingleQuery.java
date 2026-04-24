package datawave.query.cypher.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A single reading query: a sequence of reading clauses (MATCH / WITH)
 * terminated by an optional RETURN. The RETURN is only optional when the
 * query is a UNION branch whose sibling supplies one; the semantic analyzer
 * enforces the "terminal branch must RETURN" rule.
 */
public final class SingleQuery extends AstNode {

    private final List<ReadingClause> readingClauses;
    private final ReturnClause returnClause;

    public SingleQuery(SourceLocation location, List<ReadingClause> readingClauses, ReturnClause returnClause) {
        super(location);
        this.readingClauses = Collections.unmodifiableList(new ArrayList<>(readingClauses));
        this.returnClause = returnClause;
    }

    public List<ReadingClause> getReadingClauses() {
        return readingClauses;
    }

    public Optional<ReturnClause> getReturnClause() {
        return Optional.ofNullable(returnClause);
    }
}
