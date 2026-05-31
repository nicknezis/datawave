package datawave.query.cypher.ast;

import java.util.Objects;

/**
 * Source position of an AST node in the original Cypher text. Line and column are 1-based and track ANTLR's conventions.
 */
public final class SourceLocation {

    public static final SourceLocation UNKNOWN = new SourceLocation(0, 0);

    private final int line;
    private final int column;

    public SourceLocation(int line, int column) {
        this.line = line;
        this.column = column;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SourceLocation)) {
            return false;
        }
        SourceLocation that = (SourceLocation) o;
        return line == that.line && column == that.column;
    }

    @Override
    public int hashCode() {
        return Objects.hash(line, column);
    }

    @Override
    public String toString() {
        return line + ":" + column;
    }
}
