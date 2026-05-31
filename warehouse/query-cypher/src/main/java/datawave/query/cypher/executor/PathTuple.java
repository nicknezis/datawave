package datawave.query.cypher.executor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.accumulo.core.security.ColumnVisibility;

/**
 * An immutable snapshot of all variable bindings for one result path through the hop chain. Keys in {@link #asMap} are Cypher variable names (for node
 * variables) or {@code "varName.propertyName"} (for relationship attribute values and post-enrichment node properties).
 *
 * <p>
 * M3 extensions:
 * <ul>
 * <li>{@link #getPath(String)} — ordered alternating list of {@link PathElement.NodeElement node} and {@link PathElement.EdgeElement edge} appearances bound to
 * a Cypher path variable (one per path-bound MATCH).</li>
 * <li>Per-path trail-history of edge fingerprints ({@link #containsEdgeId(String, long)} / {@link #recordEdgeId(String, long)}) to enforce
 * relationship-isomorphism inside a variable-length expansion (no repeated edge along the same path within the same {@code [*lo..hi]} segment).</li>
 * <li>{@link #getVisibilities()} — every contributing edge cell's {@link ColumnVisibility}, ANDed together by the transformer via
 * {@link datawave.marking.MarkingFunctions#combine} so composite-row markings reflect the union of constraints.</li>
 * </ul>
 *
 * <p>
 * Serialization is versioned: v1 = M2 values-only format ({@link #KV_SEP}/{@link #PAIR_SEP}-delimited); v2 = paths + edge-history + visibilities.
 * {@link #fromBytes} accepts both versions for forward compat; {@link #toBytes} always writes v2.
 */
public final class PathTuple {

    /** ASCII unit-separator — between key and value within one pair (v1 format). */
    static final char KV_SEP = '';

    /** ASCII record-separator — between key-value pairs (v1 format). */
    static final char PAIR_SEP = '';

    /** v2 envelope marker: any byte sequence starting with this is v2. */
    private static final byte V2_MARKER = (byte) 0x02;

    private static final PathTuple EMPTY = new PathTuple(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList());

    private final Map<String,String> values;
    private final Map<String,List<PathElement>> paths;
    private final Map<String,Set<Long>> pathEdgeIds;
    private final List<ColumnVisibility> visibilities;

    private PathTuple(Map<String,String> values, Map<String,List<PathElement>> paths, Map<String,Set<Long>> pathEdgeIds, List<ColumnVisibility> visibilities) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.paths = freezePaths(paths);
        this.pathEdgeIds = freezeEdgeIds(pathEdgeIds);
        this.visibilities = Collections.unmodifiableList(new ArrayList<>(visibilities));
    }

    private static Map<String,List<PathElement>> freezePaths(Map<String,List<PathElement>> source) {
        if (source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String,List<PathElement>> copy = new LinkedHashMap<>(source.size());
        for (Map.Entry<String,List<PathElement>> e : source.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String,Set<Long>> freezeEdgeIds(Map<String,Set<Long>> source) {
        if (source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String,Set<Long>> copy = new LinkedHashMap<>(source.size());
        for (Map.Entry<String,Set<Long>> e : source.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(e.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    public static PathTuple empty() {
        return EMPTY;
    }

    public static PathTuple of(Map<String,String> values) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            return EMPTY;
        }
        return new PathTuple(values, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList());
    }

    public static PathTuple of(Map<String,String> values, ColumnVisibility visibility) {
        Objects.requireNonNull(values, "values");
        List<ColumnVisibility> vs = visibility == null ? Collections.emptyList() : Collections.singletonList(visibility);
        return new PathTuple(values, Collections.emptyMap(), Collections.emptyMap(), vs);
    }

    /** Returns a new tuple with the given key bound to value. */
    public PathTuple extend(String key, String value) {
        Map<String,String> copy = new LinkedHashMap<>(values);
        copy.put(Objects.requireNonNull(key, "key"), value);
        return new PathTuple(copy, paths, pathEdgeIds, visibilities);
    }

    /**
     * Returns a new tuple whose bindings are the union of {@code this} and {@code other}. {@link #values} are unioned (other wins on key conflict, matching M2
     * semantics). {@link #paths} are concatenated (each side's elements appended in order). {@link #pathEdgeIds} are unioned per pathVar. {@link #visibilities}
     * are concatenated.
     */
    public PathTuple merge(PathTuple other) {
        Objects.requireNonNull(other, "other");
        if (this.values.isEmpty() && this.paths.isEmpty() && this.visibilities.isEmpty() && this.pathEdgeIds.isEmpty()) {
            return other;
        }
        if (other.values.isEmpty() && other.paths.isEmpty() && other.visibilities.isEmpty() && other.pathEdgeIds.isEmpty()) {
            return this;
        }
        Map<String,String> mergedValues = new LinkedHashMap<>(values);
        mergedValues.putAll(other.values);

        Map<String,List<PathElement>> mergedPaths = new LinkedHashMap<>(paths.size() + other.paths.size());
        for (Map.Entry<String,List<PathElement>> e : paths.entrySet()) {
            mergedPaths.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        for (Map.Entry<String,List<PathElement>> e : other.paths.entrySet()) {
            List<PathElement> existing = mergedPaths.get(e.getKey());
            if (existing == null) {
                mergedPaths.put(e.getKey(), new ArrayList<>(e.getValue()));
            } else {
                existing.addAll(e.getValue());
            }
        }

        Map<String,Set<Long>> mergedEdges = new LinkedHashMap<>(pathEdgeIds.size() + other.pathEdgeIds.size());
        for (Map.Entry<String,Set<Long>> e : pathEdgeIds.entrySet()) {
            mergedEdges.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
        }
        for (Map.Entry<String,Set<Long>> e : other.pathEdgeIds.entrySet()) {
            Set<Long> existing = mergedEdges.get(e.getKey());
            if (existing == null) {
                mergedEdges.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
            } else {
                existing.addAll(e.getValue());
            }
        }

        List<ColumnVisibility> mergedVis = new ArrayList<>(visibilities.size() + other.visibilities.size());
        mergedVis.addAll(visibilities);
        mergedVis.addAll(other.visibilities);

        return new PathTuple(mergedValues, mergedPaths, mergedEdges, mergedVis);
    }

    /** Appends one element to the named path variable's geometry. */
    public PathTuple extendPath(String pathVar, PathElement element) {
        Objects.requireNonNull(pathVar, "pathVar");
        Objects.requireNonNull(element, "element");
        Map<String,List<PathElement>> copy = new LinkedHashMap<>(paths.size() + 1);
        for (Map.Entry<String,List<PathElement>> e : paths.entrySet()) {
            copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        copy.computeIfAbsent(pathVar, k -> new ArrayList<>()).add(element);
        return new PathTuple(values, copy, pathEdgeIds, visibilities);
    }

    /** Records an edge fingerprint under the named path's trail-history. */
    public PathTuple recordEdgeId(String pathVar, long edgeId) {
        Objects.requireNonNull(pathVar, "pathVar");
        Map<String,Set<Long>> copy = new LinkedHashMap<>(pathEdgeIds.size() + 1);
        for (Map.Entry<String,Set<Long>> e : pathEdgeIds.entrySet()) {
            copy.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
        }
        copy.computeIfAbsent(pathVar, k -> new LinkedHashSet<>()).add(edgeId);
        return new PathTuple(values, paths, copy, visibilities);
    }

    public boolean containsEdgeId(String pathVar, long edgeId) {
        Set<Long> set = pathEdgeIds.get(pathVar);
        return set != null && set.contains(edgeId);
    }

    public int pathEdgeHistorySize(String pathVar) {
        Set<Long> set = pathEdgeIds.get(pathVar);
        return set == null ? 0 : set.size();
    }

    /** Returns a tuple identical to this one but extended with one more visibility. */
    public PathTuple withVisibility(ColumnVisibility cv) {
        if (cv == null) {
            return this;
        }
        List<ColumnVisibility> copy = new ArrayList<>(visibilities.size() + 1);
        copy.addAll(visibilities);
        copy.add(cv);
        return new PathTuple(values, paths, pathEdgeIds, copy);
    }

    public String get(String key) {
        return values.get(key);
    }

    public boolean contains(String key) {
        return values.containsKey(key);
    }

    public Set<String> keys() {
        return values.keySet();
    }

    public Map<String,String> asMap() {
        return values;
    }

    public List<PathElement> getPath(String pathVar) {
        List<PathElement> p = paths.get(pathVar);
        return p == null ? Collections.emptyList() : p;
    }

    public Map<String,List<PathElement>> getPaths() {
        return paths;
    }

    public List<ColumnVisibility> getVisibilities() {
        return visibilities;
    }

    // ---- serialization --------------------------------------------------

    /**
     * Serializes as v2. Layout:
     *
     * <pre>
     *   byte    version = 0x02
     *   int     valueCount
     *   repeated valueCount times:
     *     UTF key
     *     bool hasValue ; if true: UTF value
     *   int     pathCount
     *   repeated pathCount times:
     *     UTF pathVar
     *     int elementCount
     *     repeated elementCount times:
     *       byte    kind (0=NODE, 1=EDGE)
     *       NODE: UTF? variable, UTF identity, propertiesMap
     *       EDGE: UTF? variable, UTF type, UTF source, UTF sink, attributesMap
     *   int     edgeHistoryCount
     *   repeated edgeHistoryCount times:
     *     UTF pathVar
     *     int idCount
     *     repeated idCount times: long
     *   int     visibilityCount
     *   repeated visibilityCount times:
     *     int    visExprLen
     *     visExprLen bytes
     * </pre>
     */
    public byte[] toBytes() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(); DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeByte(V2_MARKER);
            dos.writeInt(values.size());
            for (Map.Entry<String,String> e : values.entrySet()) {
                dos.writeUTF(e.getKey());
                dos.writeBoolean(e.getValue() != null);
                if (e.getValue() != null) {
                    dos.writeUTF(e.getValue());
                }
            }
            dos.writeInt(paths.size());
            for (Map.Entry<String,List<PathElement>> e : paths.entrySet()) {
                dos.writeUTF(e.getKey());
                writePathElements(dos, e.getValue());
            }
            dos.writeInt(pathEdgeIds.size());
            for (Map.Entry<String,Set<Long>> e : pathEdgeIds.entrySet()) {
                dos.writeUTF(e.getKey());
                dos.writeInt(e.getValue().size());
                for (long id : e.getValue()) {
                    dos.writeLong(id);
                }
            }
            dos.writeInt(visibilities.size());
            for (ColumnVisibility cv : visibilities) {
                byte[] expr = cv.getExpression();
                dos.writeInt(expr.length);
                dos.write(expr);
            }
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writePathElements(DataOutputStream dos, List<PathElement> elements) throws IOException {
        dos.writeInt(elements.size());
        for (PathElement el : elements) {
            if (el.getKind() == PathElement.Kind.NODE) {
                PathElement.NodeElement n = (PathElement.NodeElement) el;
                dos.writeByte(0);
                writeOptionalUTF(dos, n.getVariable());
                dos.writeUTF(n.getIdentity());
                writeStringMap(dos, n.getProperties());
            } else {
                PathElement.EdgeElement edge = (PathElement.EdgeElement) el;
                dos.writeByte(1);
                writeOptionalUTF(dos, edge.getVariable());
                dos.writeUTF(edge.getType());
                dos.writeUTF(edge.getSourceIdentity());
                dos.writeUTF(edge.getSinkIdentity());
                writeStringMap(dos, edge.getAttributes());
            }
        }
    }

    private static void writeOptionalUTF(DataOutputStream dos, String s) throws IOException {
        dos.writeBoolean(s != null);
        if (s != null) {
            dos.writeUTF(s);
        }
    }

    private static String readOptionalUTF(DataInputStream dis) throws IOException {
        return dis.readBoolean() ? dis.readUTF() : null;
    }

    private static void writeStringMap(DataOutputStream dos, Map<String,String> map) throws IOException {
        dos.writeInt(map.size());
        for (Map.Entry<String,String> e : map.entrySet()) {
            dos.writeUTF(e.getKey());
            writeOptionalUTF(dos, e.getValue());
        }
    }

    private static Map<String,String> readStringMap(DataInputStream dis) throws IOException {
        int n = dis.readInt();
        Map<String,String> out = new LinkedHashMap<>(Math.max(1, n));
        for (int i = 0; i < n; i++) {
            String k = dis.readUTF();
            String v = readOptionalUTF(dis);
            out.put(k, v);
        }
        return out;
    }

    public static PathTuple fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return EMPTY;
        }
        if (bytes[0] == V2_MARKER) {
            return fromBytesV2(bytes);
        }
        return fromBytesV1(bytes);
    }

    private static PathTuple fromBytesV1(byte[] bytes) {
        String s = new String(bytes, StandardCharsets.UTF_8);
        Map<String,String> map = new LinkedHashMap<>();
        int start = 0;
        while (start < s.length()) {
            int pairEnd = s.indexOf(PAIR_SEP, start);
            if (pairEnd < 0) {
                pairEnd = s.length();
            }
            String pair = s.substring(start, pairEnd);
            int sep = pair.indexOf(KV_SEP);
            if (sep >= 0) {
                map.put(pair.substring(0, sep), pair.substring(sep + 1));
            }
            start = pairEnd + 1;
        }
        return new PathTuple(map, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList());
    }

    private static PathTuple fromBytesV2(byte[] bytes) {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte version = dis.readByte();
            if (version != V2_MARKER) {
                throw new IOException("expected v2 marker, got " + version);
            }
            int valueCount = dis.readInt();
            Map<String,String> values = new LinkedHashMap<>(Math.max(1, valueCount));
            for (int i = 0; i < valueCount; i++) {
                String key = dis.readUTF();
                String value = readOptionalUTF(dis);
                values.put(key, value);
            }
            int pathCount = dis.readInt();
            Map<String,List<PathElement>> paths = pathCount == 0 ? Collections.emptyMap() : new LinkedHashMap<>(pathCount);
            for (int i = 0; i < pathCount; i++) {
                String pathVar = dis.readUTF();
                paths.put(pathVar, readPathElements(dis));
            }
            int edgeHistoryCount = dis.readInt();
            Map<String,Set<Long>> pathEdgeIds = edgeHistoryCount == 0 ? Collections.emptyMap() : new LinkedHashMap<>(edgeHistoryCount);
            for (int i = 0; i < edgeHistoryCount; i++) {
                String pathVar = dis.readUTF();
                int idCount = dis.readInt();
                Set<Long> ids = new LinkedHashSet<>(Math.max(1, idCount));
                for (int j = 0; j < idCount; j++) {
                    ids.add(dis.readLong());
                }
                pathEdgeIds.put(pathVar, ids);
            }
            int visCount = dis.readInt();
            List<ColumnVisibility> visibilities = visCount == 0 ? Collections.emptyList() : new ArrayList<>(visCount);
            for (int i = 0; i < visCount; i++) {
                int len = dis.readInt();
                byte[] expr = new byte[len];
                dis.readFully(expr);
                visibilities.add(new ColumnVisibility(expr));
            }
            return new PathTuple(values, paths, pathEdgeIds, visibilities);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<PathElement> readPathElements(DataInputStream dis) throws IOException {
        int n = dis.readInt();
        List<PathElement> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            byte kind = dis.readByte();
            if (kind == 0) {
                String variable = readOptionalUTF(dis);
                String identity = dis.readUTF();
                Map<String,String> props = readStringMap(dis);
                out.add(new PathElement.NodeElement(variable, identity, props));
            } else {
                String variable = readOptionalUTF(dis);
                String type = dis.readUTF();
                String source = dis.readUTF();
                String sink = dis.readUTF();
                Map<String,String> attrs = readStringMap(dis);
                out.add(new PathElement.EdgeElement(variable, type, source, sink, attrs));
            }
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PathTuple)) {
            return false;
        }
        PathTuple other = (PathTuple) o;
        return values.equals(other.values) && paths.equals(other.paths);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values, paths);
    }

    @Override
    public String toString() {
        return "PathTuple" + values + (paths.isEmpty() ? "" : " paths=" + paths.keySet());
    }
}
