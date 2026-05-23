package datawave.query.cypher.executor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One vertex or edge along a bound path variable's geometry. Captured in
 * {@link PathTuple#getPath(String) PathTuple.getPath} as an alternating
 * {@code Node, Edge, Node, Edge, ..., Node} sequence so the transformer can
 * materialise {@code MATCH p = (a)-[*1..n]-(b) RETURN p} as a structured
 * record-of-records on the wire.
 */
public abstract class PathElement {

    public enum Kind {
        NODE,
        EDGE
    }

    private final Kind kind;

    private PathElement(Kind kind) {
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    /** Vertex appearance along the path. {@code properties} may be empty before enrichment. */
    public static final class NodeElement extends PathElement {

        private final String variable;
        private final String identity;
        private final Map<String,String> properties;

        public NodeElement(String variable, String identity, Map<String,String> properties) {
            super(Kind.NODE);
            this.variable = variable;
            this.identity = Objects.requireNonNull(identity, "identity");
            this.properties = properties == null ? Collections.emptyMap()
                            : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        }

        /** May be {@code null} for anonymous junctions inside variable-length expansions. */
        public String getVariable() {
            return variable;
        }

        public String getIdentity() {
            return identity;
        }

        public Map<String,String> getProperties() {
            return properties;
        }
    }

    /** Edge appearance along the path. */
    public static final class EdgeElement extends PathElement {

        private final String variable;
        private final String type;
        private final String sourceIdentity;
        private final String sinkIdentity;
        private final Map<String,String> attributes;

        public EdgeElement(String variable, String type, String sourceIdentity, String sinkIdentity, Map<String,String> attributes) {
            super(Kind.EDGE);
            this.variable = variable;
            this.type = Objects.requireNonNull(type, "type");
            this.sourceIdentity = Objects.requireNonNull(sourceIdentity, "sourceIdentity");
            this.sinkIdentity = Objects.requireNonNull(sinkIdentity, "sinkIdentity");
            this.attributes = attributes == null ? Collections.emptyMap()
                            : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }

        /** May be {@code null} for anonymous relationships inside variable-length expansions. */
        public String getVariable() {
            return variable;
        }

        public String getType() {
            return type;
        }

        public String getSourceIdentity() {
            return sourceIdentity;
        }

        public String getSinkIdentity() {
            return sinkIdentity;
        }

        public Map<String,String> getAttributes() {
            return attributes;
        }
    }
}
