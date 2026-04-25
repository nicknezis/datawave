package datawave.query.cypher.planner;

import java.util.Objects;

/**
 * One column in the RETURN clause, resolved to its bound variable, the
 * property name (or null when projecting the bound entity itself), and the
 * exposed alias.
 *
 * In M1 only property projections of node identity properties and edge
 * attributes are honored; node bindings without a property and arbitrary
 * expressions are rejected at plan time.
 */
public final class Projection {

    /** What kind of bound entity the projection's variable refers to. */
    public enum Kind {
        NODE_PROPERTY,
        REL_PROPERTY
    }

    private final String alias;
    private final String variable;
    private final String property;
    private final Kind kind;

    public Projection(String alias, String variable, String property, Kind kind) {
        this.alias = Objects.requireNonNull(alias, "alias");
        this.variable = Objects.requireNonNull(variable, "variable");
        this.property = Objects.requireNonNull(property, "property");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public String getAlias() {
        return alias;
    }

    public String getVariable() {
        return variable;
    }

    public String getProperty() {
        return property;
    }

    public Kind getKind() {
        return kind;
    }
}
