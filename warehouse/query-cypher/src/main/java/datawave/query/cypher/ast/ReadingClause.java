package datawave.query.cypher.ast;

/** Marker type for clauses that can appear in the reading portion of a query. */
public abstract class ReadingClause extends AstNode {
    protected ReadingClause(SourceLocation location) {
        super(location);
    }
}
