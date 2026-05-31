package datawave.query.cypher.transformer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import datawave.webservice.xml.util.StringMapAdapter;

/**
 * One result row produced by a Cypher query: an ordered map keyed by the RETURN clause's exposed alias whose values are typed {@link CypherValue}s, plus the
 * combined column-visibility markings of every cell that contributed to the row.
 *
 * <p>
 * M3 widens column values from plain strings to {@link CypherValue} so aggregate results keep their numeric type and {@code RETURN p} can carry a structured
 * {@link CypherValue.PathValue}. JAXB serialises the columns as a list of {@link ColumnEntry} elements; in-memory access via {@link #getColumns()} is the
 * {@code Map<String,CypherValue>}.
 */
@XmlAccessorType(XmlAccessType.NONE)
public final class CypherRow implements Serializable {

    private static final long serialVersionUID = 1L;

    private Map<String,CypherValue> columns;
    private Map<String,String> markings;

    /** No-arg constructor required for JAXB. */
    public CypherRow() {
        this.columns = Collections.emptyMap();
        this.markings = Collections.emptyMap();
    }

    public CypherRow(Map<String,CypherValue> columns, Map<String,String> markings) {
        this.columns = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(columns, "columns")));
        this.markings = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(markings, "markings")));
    }

    /** In-memory accessor; not the JAXB element (see {@link #getColumnEntries}). */
    public Map<String,CypherValue> getColumns() {
        return columns;
    }

    public void setColumns(Map<String,CypherValue> columns) {
        this.columns = columns == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    }

    /** JAXB serialisation view of {@link #columns}. */
    @XmlElementWrapper(name = "Columns")
    @XmlElement(name = "Column")
    public List<ColumnEntry> getColumnEntries() {
        List<ColumnEntry> out = new ArrayList<>(columns.size());
        for (Map.Entry<String,CypherValue> e : columns.entrySet()) {
            out.add(new ColumnEntry(e.getKey(), e.getValue()));
        }
        return out;
    }

    public void setColumnEntries(List<ColumnEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            this.columns = Collections.emptyMap();
            return;
        }
        Map<String,CypherValue> map = new LinkedHashMap<>(entries.size());
        for (ColumnEntry e : entries) {
            map.put(e.getName(), e.getValue());
        }
        this.columns = Collections.unmodifiableMap(map);
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
