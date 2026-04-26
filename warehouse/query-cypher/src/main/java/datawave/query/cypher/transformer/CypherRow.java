package datawave.query.cypher.transformer;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import datawave.webservice.xml.util.StringMapAdapter;

/**
 * One result row produced by a Cypher query: an ordered map keyed by the
 * RETURN clause's exposed alias, plus the combined column-visibility
 * markings of every cell that contributed to the row.
 *
 * <p>M1 holds values as plain strings; the M4 wire-format work
 * (CypherQueryResponseBase / DefaultCypherQueryResponse) will introduce
 * typed columns and nested path objects.
 */
@XmlAccessorType(XmlAccessType.NONE)
public final class CypherRow implements Serializable {

    private static final long serialVersionUID = 1L;

    private Map<String,String> columns;
    private Map<String,String> markings;

    /** No-arg constructor required for JAXB. */
    public CypherRow() {
        this.columns = Collections.emptyMap();
        this.markings = Collections.emptyMap();
    }

    public CypherRow(Map<String,String> columns, Map<String,String> markings) {
        this.columns = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(columns, "columns")));
        this.markings = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(markings, "markings")));
    }

    @XmlJavaTypeAdapter(StringMapAdapter.class)
    @XmlElement(name = "Columns")
    public Map<String,String> getColumns() {
        return columns;
    }

    public void setColumns(Map<String,String> columns) {
        this.columns = columns == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    }

    @XmlJavaTypeAdapter(StringMapAdapter.class)
    @XmlElement(name = "Markings")
    public Map<String,String> getMarkings() {
        return markings;
    }

    public void setMarkings(Map<String,String> markings) {
        this.markings = markings == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(markings));
    }

    @Override
    public String toString() {
        return "CypherRow" + columns + " markings=" + markings;
    }
}
