package datawave.query.cypher.ast;

/** Marker base class for all expression nodes in the AST. */
public abstract class Expression extends AstNode {
    protected Expression(SourceLocation location) {
        super(location);
    }
}
