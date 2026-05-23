package datawave.query.cypher.transformer;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;


import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlSeeAlso;

/**
 * A typed column value carried inside {@link CypherRow}. M3 widens the row's
 * column map from {@code Map<String,String>} to {@code Map<String,CypherValue>}
 * so that:
 * <ul>
 *   <li>numeric aggregate results ({@code count(*)}, {@code sum}, {@code avg})
 *       keep their type instead of being coerced to a string;</li>
 *   <li>{@code RETURN p} can carry a structured {@link PathValue}.</li>
 * </ul>
 *
 * <p>The factory methods preserve M2-shaped output for the all-string case:
 * {@link #string(String)} produces a value that survives a JAXB round-trip
 * identically to the M2 string-only response.
 */
@XmlAccessorType(XmlAccessType.NONE)
@XmlSeeAlso({CypherValue.StringValue.class, CypherValue.LongValue.class, CypherValue.DecimalValue.class, CypherValue.PathValue.class})
public abstract class CypherValue implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        STRING,
        LONG,
        DECIMAL,
        PATH
    }

    public abstract Kind getKind();

    /** Convenience for callers that only need a string view (e.g. legacy paths). */
    public abstract String asString();

    public static CypherValue string(String s) {
        return new StringValue(s);
    }

    public static CypherValue longValue(long v) {
        return new LongValue(v);
    }

    public static CypherValue decimal(BigDecimal v) {
        return new DecimalValue(v);
    }

    public static CypherValue path(List<PathRecord> records) {
        return new PathValue(records);
    }

    // ---- subtypes -------------------------------------------------------

    @XmlAccessorType(XmlAccessType.NONE)
    public static final class StringValue extends CypherValue {

        private static final long serialVersionUID = 1L;

        @XmlElement(name = "Value")
        private String value;

        public StringValue() {}

        public StringValue(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        @Override
        public Kind getKind() {
            return Kind.STRING;
        }

        @Override
        public String asString() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof StringValue && Objects.equals(value, ((StringValue) o).value);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(value);
        }

        @Override
        public String toString() {
            return value == null ? "null" : value;
        }
    }

    @XmlAccessorType(XmlAccessType.NONE)
    public static final class LongValue extends CypherValue {

        private static final long serialVersionUID = 1L;

        @XmlElement(name = "Value")
        private long value;

        public LongValue() {}

        public LongValue(long value) {
            this.value = value;
        }

        public long getValue() {
            return value;
        }

        public void setValue(long value) {
            this.value = value;
        }

        @Override
        public Kind getKind() {
            return Kind.LONG;
        }

        @Override
        public String asString() {
            return Long.toString(value);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof LongValue && value == ((LongValue) o).value;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(value);
        }

        @Override
        public String toString() {
            return Long.toString(value);
        }
    }

    @XmlAccessorType(XmlAccessType.NONE)
    public static final class DecimalValue extends CypherValue {

        private static final long serialVersionUID = 1L;

        @XmlElement(name = "Value")
        private BigDecimal value;

        public DecimalValue() {}

        public DecimalValue(BigDecimal value) {
            this.value = value;
        }

        public BigDecimal getValue() {
            return value;
        }

        public void setValue(BigDecimal value) {
            this.value = value;
        }

        @Override
        public Kind getKind() {
            return Kind.DECIMAL;
        }

        @Override
        public String asString() {
            return value == null ? null : value.toPlainString();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof DecimalValue)) {
                return false;
            }
            BigDecimal other = ((DecimalValue) o).value;
            if (value == null || other == null) {
                return value == other;
            }
            return value.compareTo(other) == 0;
        }

        @Override
        public int hashCode() {
            return value == null ? 0 : value.stripTrailingZeros().hashCode();
        }

        @Override
        public String toString() {
            return asString();
        }
    }

    @XmlAccessorType(XmlAccessType.NONE)
    public static final class PathValue extends CypherValue {

        private static final long serialVersionUID = 1L;

        @XmlElement(name = "Element")
        private List<PathRecord> elements;

        public PathValue() {
            this.elements = Collections.emptyList();
        }

        public PathValue(List<PathRecord> elements) {
            this.elements = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(elements, "elements")));
        }

        public List<PathRecord> getElements() {
            return elements;
        }

        public void setElements(List<PathRecord> elements) {
            this.elements = elements == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(elements));
        }

        @Override
        public Kind getKind() {
            return Kind.PATH;
        }

        @Override
        public String asString() {
            StringBuilder sb = new StringBuilder();
            for (PathRecord r : elements) {
                if (sb.length() > 0) {
                    sb.append(" -> ");
                }
                sb.append(r);
            }
            return sb.toString();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PathValue && Objects.equals(elements, ((PathValue) o).elements);
        }

        @Override
        public int hashCode() {
            return elements == null ? 0 : elements.hashCode();
        }

        @Override
        public String toString() {
            return asString();
        }
    }
}
