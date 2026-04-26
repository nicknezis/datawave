package datawave.query.cypher.executor;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable snapshot of all variable bindings for one result path through
 * the hop chain. Keys are Cypher variable names (for node variables) or
 * {@code "varName.propertyName"} (for relationship attribute values).
 *
 * <p>Serialization contract for the Accumulo iterator:
 * entries are stored as UTF-8 bytes with {@link #PAIR_SEP} between pairs
 * and {@link #KV_SEP} between key and value within a pair.  Keys and values
 * must not contain these control characters; Cypher identifiers and
 * DataWave edge values satisfy this constraint in practice.
 */
public final class PathTuple {

    /** ASCII unit-separator — between key and value within one pair. */
    static final char KV_SEP = '';

    /** ASCII record-separator — between key-value pairs. */
    static final char PAIR_SEP = '';

    private static final PathTuple EMPTY = new PathTuple(Collections.emptyMap());

    private final Map<String,String> values;

    private PathTuple(Map<String,String> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static PathTuple empty() {
        return EMPTY;
    }

    public static PathTuple of(Map<String,String> values) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            return EMPTY;
        }
        return new PathTuple(values);
    }

    /** Returns a new tuple with the given key bound to value. */
    public PathTuple extend(String key, String value) {
        Map<String,String> copy = new LinkedHashMap<>(values);
        copy.put(Objects.requireNonNull(key, "key"), value);
        return new PathTuple(copy);
    }

    /** Returns a new tuple whose bindings are the union of this and other. */
    public PathTuple merge(PathTuple other) {
        Objects.requireNonNull(other, "other");
        if (this.values.isEmpty()) {
            return other;
        }
        if (other.values.isEmpty()) {
            return this;
        }
        Map<String,String> copy = new LinkedHashMap<>(values);
        copy.putAll(other.values);
        return new PathTuple(copy);
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

    // ---- serialization --------------------------------------------------

    public byte[] toBytes() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String,String> e : values.entrySet()) {
            if (sb.length() > 0) {
                sb.append(PAIR_SEP);
            }
            sb.append(e.getKey()).append(KV_SEP);
            if (e.getValue() != null) {
                sb.append(e.getValue());
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static PathTuple fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return EMPTY;
        }
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
        return new PathTuple(map);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PathTuple)) {
            return false;
        }
        return values.equals(((PathTuple) o).values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "PathTuple" + values;
    }
}
