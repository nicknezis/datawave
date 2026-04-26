package datawave.query.cypher.transformer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.xml.bind.annotation.XmlAccessOrder;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorOrder;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlRootElement;

import datawave.webservice.result.BaseQueryResponse;

/**
 * Minimal M1 wire response: a list of {@link CypherRow rows} plus the
 * RETURN clause's column names. The proper {@code CypherQueryResponseBase}
 * + {@code DefaultCypherQueryResponse} pair lives in M4 (per feasibility
 * decision #3) where typed columns and nested path objects land. Until
 * then this wrapper keeps the BaseQueryLogic plumbing happy.
 */
@XmlRootElement(name = "CypherQueryResponse")
@XmlAccessorType(XmlAccessType.NONE)
@XmlAccessorOrder(XmlAccessOrder.ALPHABETICAL)
public class CypherQueryResponse extends BaseQueryResponse {

    private static final long serialVersionUID = 1L;

    @XmlElementWrapper(name = "Columns")
    @XmlElement(name = "Column")
    private List<String> columns = Collections.emptyList();

    @XmlElementWrapper(name = "Rows")
    @XmlElement(name = "Row")
    private List<CypherRow> rows = new ArrayList<>();

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
    }

    public List<CypherRow> getRows() {
        return rows;
    }

    public void setRows(List<CypherRow> rows) {
        this.rows = rows;
    }
}
