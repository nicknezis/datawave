package datawave.query.cypher.mapping;

/**
 * Whether an edge type is stored undirected (one logical edge written as two physical rows in lex order) or directed (one physical row, source-to-sink
 * meaningful). Drives canonicalization for undirected Cypher patterns so results are not double-counted.
 */
public enum EdgeDirection {
    DIRECTED, UNDIRECTED
}
