package datawave.query.cypher.executor;

import java.util.Objects;

import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.ColumnVisibility;

/**
 * One scanned edge cell, paired with its decoded {@link ColumnVisibility} so
 * downstream tuple construction can compose visibility without re-decoding.
 */
public final class EdgeRow {

    private final Key key;
    private final Value value;
    private final ColumnVisibility visibility;

    public EdgeRow(Key key, Value value) {
        this.key = Objects.requireNonNull(key, "key");
        this.value = Objects.requireNonNull(value, "value");
        this.visibility = key.getColumnVisibility() == null ? null : new ColumnVisibility(key.getColumnVisibility());
    }

    public Key getKey() {
        return key;
    }

    public Value getValue() {
        return value;
    }

    public ColumnVisibility getVisibility() {
        return visibility;
    }
}
