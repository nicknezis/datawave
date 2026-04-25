package datawave.query.cypher.mapping;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One Cypher label's mapping into the underlying DataWave shard/event store.
 * <p>
 * The {@link #getIdentityProperty()} is the Cypher property that, on disk,
 * is identical to the value placed into the edge SOURCE/SINK column. In M1
 * this is the only property that can be referenced in WHERE or RETURN
 * without triggering a shard-table join (which is M2 work). Other property
 * names are recorded in {@link #getProperties()} so M2's enrichment step
 * knows the on-disk field name to look up.
 */
public final class NodeMapping {

    private final String label;
    private final String dataType;
    private final String identityProperty;
    private final String identityField;
    private final Map<String,String> properties;

    public NodeMapping(String label, String dataType, String identityProperty, String identityField, Map<String,String> properties) {
        this.label = Objects.requireNonNull(label, "label");
        this.dataType = Objects.requireNonNull(dataType, "dataType");
        this.identityProperty = Objects.requireNonNull(identityProperty, "identityProperty");
        this.identityField = Objects.requireNonNull(identityField, "identityField");
        Map<String,String> copy = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
        copy.putIfAbsent(identityProperty, identityField);
        this.properties = Collections.unmodifiableMap(copy);
    }

    public String getLabel() {
        return label;
    }

    public String getDataType() {
        return dataType;
    }

    public String getIdentityProperty() {
        return identityProperty;
    }

    public String getIdentityField() {
        return identityField;
    }

    public Map<String,String> getProperties() {
        return properties;
    }

    public boolean isIdentityProperty(String propertyName) {
        return identityProperty.equals(propertyName);
    }

    public Optional<String> resolveField(String propertyName) {
        return Optional.ofNullable(properties.get(propertyName));
    }
}
