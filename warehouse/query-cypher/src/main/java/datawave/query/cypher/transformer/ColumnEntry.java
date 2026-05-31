package datawave.query.cypher.transformer;

import java.io.Serializable;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElements;

/**
 * One (alias, typed-value) pair inside a {@link CypherRow}. {@code @XmlElements} declares the polymorphic mapping from each {@link CypherValue} subtype to an
 * XML element name so the subtypes need not be root elements themselves.
 */
@XmlAccessorType(XmlAccessType.NONE)
public final class ColumnEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    @XmlElement(name = "Name")
    private String name;

    @XmlElements({@XmlElement(name = "String", type = CypherValue.StringValue.class), @XmlElement(name = "Long", type = CypherValue.LongValue.class),
            @XmlElement(name = "Decimal", type = CypherValue.DecimalValue.class), @XmlElement(name = "Path", type = CypherValue.PathValue.class)})
    private CypherValue value;

    public ColumnEntry() {}

    public ColumnEntry(String name, CypherValue value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public CypherValue getValue() {
        return value;
    }

    public void setValue(CypherValue value) {
        this.value = value;
    }
}
