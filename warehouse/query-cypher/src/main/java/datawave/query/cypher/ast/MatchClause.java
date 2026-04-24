package datawave.query.cypher.ast;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class MatchClause extends ReadingClause {

    private final boolean optional;
    private final List<Pattern> patterns;
    private final Expression where;

    public MatchClause(SourceLocation location, boolean optional, List<Pattern> patterns, Expression where) {
        super(location);
        this.optional = optional;
        this.patterns = Collections.unmodifiableList(patterns);
        this.where = where;
    }

    public boolean isOptional() {
        return optional;
    }

    public List<Pattern> getPatterns() {
        return patterns;
    }

    public Optional<Expression> getWhere() {
        return Optional.ofNullable(where);
    }
}
