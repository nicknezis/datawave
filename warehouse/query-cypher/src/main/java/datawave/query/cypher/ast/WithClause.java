package datawave.query.cypher.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class WithClause extends ReadingClause {

    private final boolean distinct;
    private final List<ProjectionItem> projections;
    private final List<SortItem> orderBy;
    private final Expression skip;
    private final Expression limit;
    private final Expression where;
    private final boolean projectAll;

    public WithClause(SourceLocation location, boolean distinct, boolean projectAll, List<ProjectionItem> projections, List<SortItem> orderBy,
                    Expression skip, Expression limit, Expression where) {
        super(location);
        this.distinct = distinct;
        this.projectAll = projectAll;
        this.projections = Collections.unmodifiableList(new ArrayList<>(projections));
        this.orderBy = Collections.unmodifiableList(new ArrayList<>(orderBy));
        this.skip = skip;
        this.limit = limit;
        this.where = where;
    }

    public boolean isDistinct() {
        return distinct;
    }

    public boolean isProjectAll() {
        return projectAll;
    }

    public List<ProjectionItem> getProjections() {
        return projections;
    }

    public List<SortItem> getOrderBy() {
        return orderBy;
    }

    public Optional<Expression> getSkip() {
        return Optional.ofNullable(skip);
    }

    public Optional<Expression> getLimit() {
        return Optional.ofNullable(limit);
    }

    public Optional<Expression> getWhere() {
        return Optional.ofNullable(where);
    }
}
