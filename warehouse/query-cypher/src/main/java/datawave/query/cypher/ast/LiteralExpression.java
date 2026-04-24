package datawave.query.cypher.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed literal value. The {@link Kind} tag lets consumers handle primitives
 * uniformly without instanceof ladders; {@code STRING}, {@code INTEGER},
 * {@code DECIMAL}, {@code BOOLEAN}, {@code NULL}, {@code LIST}, {@code MAP}.
 */
public final class LiteralExpression extends Expression {

    public enum Kind {
        STRING,
        INTEGER,
        DECIMAL,
        BOOLEAN,
        NULL,
        LIST,
        MAP
    }

    private final Kind kind;
    private final Object value;

    private LiteralExpression(SourceLocation location, Kind kind, Object value) {
        super(location);
        this.kind = kind;
        this.value = value;
    }

    public static LiteralExpression ofString(SourceLocation location, String value) {
        return new LiteralExpression(location, Kind.STRING, value);
    }

    public static LiteralExpression ofInteger(SourceLocation location, long value) {
        return new LiteralExpression(location, Kind.INTEGER, value);
    }

    public static LiteralExpression ofDecimal(SourceLocation location, double value) {
        return new LiteralExpression(location, Kind.DECIMAL, value);
    }

    public static LiteralExpression ofBoolean(SourceLocation location, boolean value) {
        return new LiteralExpression(location, Kind.BOOLEAN, value);
    }

    public static LiteralExpression ofNull(SourceLocation location) {
        return new LiteralExpression(location, Kind.NULL, null);
    }

    public static LiteralExpression ofList(SourceLocation location, List<Expression> items) {
        return new LiteralExpression(location, Kind.LIST, Collections.unmodifiableList(new ArrayList<>(items)));
    }

    public static LiteralExpression ofMap(SourceLocation location, Map<String,Expression> entries) {
        return new LiteralExpression(location, Kind.MAP, Collections.unmodifiableMap(new LinkedHashMap<>(entries)));
    }

    public Kind getKind() {
        return kind;
    }

    public Object getValue() {
        return value;
    }

    @SuppressWarnings("unchecked")
    public List<Expression> asList() {
        return (List<Expression>) value;
    }

    @SuppressWarnings("unchecked")
    public Map<String,Expression> asMap() {
        return (Map<String,Expression>) value;
    }
}
