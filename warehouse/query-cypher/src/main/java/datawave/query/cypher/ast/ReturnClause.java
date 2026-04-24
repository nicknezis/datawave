package datawave.query.cypher.ast;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class ReturnClause extends AstNode {

    private final boolean distinct;
    private final boolean projectAll;
    private final List<ProjectionItem> projections;
    private final List<SortItem> orderBy;
    private final Expression skip;
    private final Expression limit;

    public ReturnClause(SourceLocation location, boolean distinct, boolean projectAll, List<ProjectionItem> projections, List<SortItem> orderBy,
                    Expression skip, Expression limit) {
        super(location);
        this.distinct = distinct;
        this.projectAll = projectAll;
        this.projections = Collections.unmodifiableList(projections);
        this.orderBy = Collections.unmodifiableList(orderBy);
        this.skip = skip;
        this.limit = limit;
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
}
