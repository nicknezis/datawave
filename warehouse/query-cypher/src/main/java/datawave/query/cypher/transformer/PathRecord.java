package datawave.query.cypher.transformer;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import datawave.webservice.xml.util.StringMapAdapter;

/**
 * One node or edge appearance inside a {@link CypherValue.PathValue}. A flat
 * record-of-strings keeps the wire shape simple: clients can render the
 * alternating node/edge sequence by walking {@link #getElements} on the
 * enclosing value and dispatching on {@link #getKind}.
 */
@XmlAccessorType(XmlAccessType.NONE)
public final class PathRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        NODE,
        EDGE
    }

    @XmlElement(name = "Kind")
    private Kind kind;

    @XmlElement(name = "Variable")
    private String variable;

    /** Vertex: the identity property value. */
    @XmlElement(name = "Identity")
    private String identity;

    /** Edge: the Cypher relationship type. */
    @XmlElement(name = "Type")
    private String type;

    /** Edge: source vertex identity. */
    @XmlElement(name = "Source")
    private String source;

    /** Edge: sink vertex identity. */
    @XmlElement(name = "Sink")
    private String sink;

    @XmlJavaTypeAdapter(StringMapAdapter.class)
    @XmlElement(name = "Properties")
    private Map<String,String> properties;

    public PathRecord() {
        this.properties = Collections.emptyMap();
    }

    public static PathRecord node(String variable, String identity, Map<String,String> properties) {
        PathRecord r = new PathRecord();
        r.kind = Kind.NODE;
        r.variable = variable;
        r.identity = identity;
        r.properties = properties == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        return r;
    }

    public static PathRecord edge(String variable, String type, String source, String sink, Map<String,String> attributes) {
        PathRecord r = new PathRecord();
        r.kind = Kind.EDGE;
        r.variable = variable;
        r.type = type;
        r.source = source;
        r.sink = sink;
        r.properties = attributes == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        return r;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public String getVariable() {
        return variable;
    }

    public void setVariable(String variable) {
        this.variable = variable;
    }

    public String getIdentity() {
        return identity;
    }

    public void setIdentity(String identity) {
        this.identity = identity;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSink() {
        return sink;
    }

    public void setSink(String sink) {
        this.sink = sink;
    }

    public Map<String,String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String,String> properties) {
        this.properties = properties == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PathRecord)) {
            return false;
        }
        PathRecord other = (PathRecord) o;
        return kind == other.kind && java.util.Objects.equals(variable, other.variable) && java.util.Objects.equals(identity, other.identity)
                        && java.util.Objects.equals(type, other.type) && java.util.Objects.equals(source, other.source)
                        && java.util.Objects.equals(sink, other.sink) && java.util.Objects.equals(properties, other.properties);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(kind, variable, identity, type, source, sink, properties);
    }

    @Override
    public String toString() {
        if (kind == Kind.NODE) {
            return "(" + (variable == null ? "" : variable) + ":" + identity + ")";
        }
        return "[" + (variable == null ? "" : variable) + ":" + type + "]";
    }
}
