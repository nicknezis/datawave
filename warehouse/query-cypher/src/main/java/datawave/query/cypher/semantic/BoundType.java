package datawave.query.cypher.semantic;

/**
 * The kind of entity a Cypher variable is bound to. M0 distinguishes the four forms used by the read-only planner: NODE, RELATIONSHIP, PATH, and VALUE
 * (anything produced by a WITH/RETURN projection expression, e.g. a scalar, aggregate result, or list).
 */
public enum BoundType {
    NODE, RELATIONSHIP, PATH, VALUE
}
