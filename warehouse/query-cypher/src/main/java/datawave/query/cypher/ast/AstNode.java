package datawave.query.cypher.ast;

/**
 * Common base type for every AST node. All concrete node types are immutable and carry a {@link SourceLocation} so semantic errors can point back to the
 * original Cypher text.
 */
public abstract class AstNode {

    private final SourceLocation location;

    protected AstNode(SourceLocation location) {
        this.location = location == null ? SourceLocation.UNKNOWN : location;
    }

    public final SourceLocation getLocation() {
        return location;
    }
}
