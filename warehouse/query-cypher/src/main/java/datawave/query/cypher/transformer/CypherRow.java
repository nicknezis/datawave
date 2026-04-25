package datawave.query.cypher.transformer;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One result row produced by a Cypher query: an ordered map keyed by the
 * RETURN clause's exposed alias, plus the combined column-visibility
 * markings of every cell that contributed to the row.
 *
 * <p>M1 holds values as plain strings; the M4 wire-format work
 * (CypherQueryResponseBase / DefaultCypherQueryResponse) will introduce
 * typed columns and nested path objects.
 */
public final class CypherRow implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Map<String,String> columns;
    private final Map<String,String> markings;

    public CypherRow(Map<String,String> columns, Map<String,String> markings) {
        this.columns = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(columns, "columns")));
        this.markings = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(markings, "markings")));
    }

    public Map<String,String> getColumns() {
        return columns;
    }

    public Map<String,String> getMarkings() {
        return markings;
    }

    @Override
    public String toString() {
        return "CypherRow" + columns + " markings=" + markings;
    }
}
