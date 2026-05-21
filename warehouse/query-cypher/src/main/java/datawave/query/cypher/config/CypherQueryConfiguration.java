package datawave.query.cypher.config;

import java.util.Collections;
import java.util.List;

import datawave.core.query.configuration.GenericQueryConfiguration;
import datawave.core.query.logic.BaseQueryLogic;
import datawave.query.cypher.executor.PathTuple;
import datawave.query.cypher.physical.HopTranslation;
import datawave.query.cypher.planner.CypherPlan;

/**
 * Carries the planned-and-translated artifacts for a Cypher query through
 * the {@code initialize()} → {@code setupQuery()} → {@code getTransformer()}
 * lifecycle.
 *
 * <p>The plan and hop translations are populated by
 * {@code CypherQueryLogic.initialize()} and consumed by both
 * {@code setupQuery()} (for ranges + filter JEXL) and the transformer (for
 * projection metadata). The result tuples are populated by
 * {@code setupQuery()} (after the full hop chain executes) and consumed by
 * the transformer.
 */
public class CypherQueryConfiguration extends GenericQueryConfiguration {

    private static final long serialVersionUID = 1L;

    /** Cypher query text as supplied by the caller. */
    private String cypherText;

    /** Resolved logical plan (transient — rebuilt on resume). */
    private transient CypherPlan plan;

    /**
     * One pre-built translation per hop (index 0 = first hop).
     * Subsequent hops are re-translated at scan time with frontier values.
     * Transient — derived from the plan.
     */
    private transient List<HopTranslation> hopTranslations = Collections.emptyList();

    /**
     * Fully post-processed result tuples (after multi-hop join, enrichment,
     * DISTINCT, ORDER BY, SKIP). Populated by {@code setupQuery()} and
     * consumed by the iterator + transformer. Transient.
     */
    private transient List<PathTuple> resultTuples = Collections.emptyList();

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
        this.hopTranslations = other.hopTranslations;
        this.resultTuples = other.resultTuples;
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

    public List<HopTranslation> getHopTranslations() {
        return hopTranslations;
    }

    public void setHopTranslations(List<HopTranslation> hopTranslations) {
        this.hopTranslations = hopTranslations == null ? Collections.emptyList() : Collections.unmodifiableList(hopTranslations);
    }

    /**
     * @deprecated use {@link #getHopTranslations()} and {@link #setHopTranslations}
     */
    @Deprecated
    public HopTranslation getHopTranslation() {
        return hopTranslations.isEmpty() ? null : hopTranslations.get(0);
    }

    /**
     * @deprecated use {@link #setHopTranslations}
     */
    @Deprecated
    public void setHopTranslation(HopTranslation hopTranslation) {
        setHopTranslations(hopTranslation == null ? Collections.emptyList() : Collections.singletonList(hopTranslation));
    }

    public List<PathTuple> getResultTuples() {
        return resultTuples;
    }

    public void setResultTuples(List<PathTuple> resultTuples) {
        this.resultTuples = resultTuples == null ? Collections.emptyList() : Collections.unmodifiableList(resultTuples);
    }
}
