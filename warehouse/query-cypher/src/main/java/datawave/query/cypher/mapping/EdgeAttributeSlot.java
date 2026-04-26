package datawave.query.cypher.mapping;

/**
 * The three positional attribute slots that a DataWave edge row carries on
 * its column qualifier. Cypher relationship-property names map onto one of
 * these slots via {@link RelMapping#getAttributeMappings()}.
 */
public enum EdgeAttributeSlot {
    ATTRIBUTE1,
    ATTRIBUTE2,
    ATTRIBUTE3
}
