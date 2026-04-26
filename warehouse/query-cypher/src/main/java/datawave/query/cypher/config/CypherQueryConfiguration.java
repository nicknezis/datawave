package datawave.query.cypher.config;

import datawave.core.query.configuration.GenericQueryConfiguration;
import datawave.core.query.logic.BaseQueryLogic;
import datawave.query.cypher.physical.HopTranslation;
import datawave.query.cypher.planner.CypherPlan;

/**
 * Carries the planned-and-translated artifacts for a Cypher query through
 * the {@code initialize()} → {@code setupQuery()} → {@code getTransformer()}
 * lifecycle. The plan and translation are populated by
 * {@code CypherQueryLogic.initialize()} and consumed by both
 * {@code setupQuery} (for ranges + filter JEXL) and the transformer (for
 * projection metadata + endpoint-swap awareness).
 */
public class CypherQueryConfiguration extends GenericQueryConfiguration {

    private static final long serialVersionUID = 1L;

    /** Cypher query text as supplied by the caller. */
    private String cypherText;

    /** Resolved logical plan (transient — rebuilt on resume). */
    private transient CypherPlan plan;

    /** Translated physical hop (transient — derived from the plan). */
    private transient HopTranslation hopTranslation;

    public CypherQueryConfiguration() {
        super();
    }

    public CypherQueryConfiguration(BaseQueryLogic<?> logic) {
        super(logic);
    }

    public CypherQueryConfiguration(CypherQueryConfiguration other) {
        super(other);
        this.cypherText = other.cypherText;
        this.plan = other.plan;
        this.hopTranslation = other.hopTranslation;
    }

    public String getCypherText() {
        return cypherText;
    }

    public void setCypherText(String cypherText) {
        this.cypherText = cypherText;
    }

    public CypherPlan getPlan() {
        return plan;
    }

    public void setPlan(CypherPlan plan) {
        this.plan = plan;
    }

    public HopTranslation getHopTranslation() {
        return hopTranslation;
    }

    public void setHopTranslation(HopTranslation hopTranslation) {
        this.hopTranslation = hopTranslation;
    }
}
