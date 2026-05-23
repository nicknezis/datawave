package datawave.query.cypher.tables;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;

import com.google.common.collect.Iterators;

import datawave.core.common.connection.AccumuloConnectionFactory.Priority;
import datawave.core.query.configuration.GenericQueryConfiguration;
import datawave.core.query.logic.BaseQueryLogic;
import datawave.core.query.logic.QueryLogicTransformer;
import datawave.microservice.query.Query;
import datawave.query.cypher.CypherFrontEnd;
import datawave.query.cypher.config.CypherQueryConfiguration;
import datawave.query.cypher.executor.ExecutorLimits;
import datawave.query.cypher.executor.MultiHopExecutor;
import datawave.query.cypher.executor.PathTuple;
import datawave.query.cypher.executor.ShardEnrichmentService;
import datawave.query.cypher.mapping.GraphSchema;
import datawave.query.cypher.mapping.GraphSchemaLoader;
import datawave.query.cypher.physical.HopTranslation;
import datawave.query.cypher.physical.HopTranslator;
import datawave.query.cypher.planner.CypherPlan;
import datawave.query.cypher.planner.CypherPlanner;
import datawave.query.cypher.planner.HopSpec;
import datawave.query.cypher.planner.Projection;
import datawave.query.cypher.transformer.CypherQueryTransformer;
import datawave.query.cypher.transformer.CypherRow;

/**
 * Cypher query logic for M2: multi-hop MATCH, WITH, ORDER BY, SKIP, DISTINCT.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #initialize}: parses the Cypher text, runs the planner to
 *       produce a {@link CypherPlan}, pre-translates hop 0, and stores the
 *       plan and initial translation in the returned
 *       {@link CypherQueryConfiguration}; later hops are translated during
 *       frontier execution at runtime.</li>
 *   <li>{@link #setupQuery}: runs the full hop chain via
 *       {@link MultiHopExecutor} (in-memory join for multi-hop), applies
 *       DISTINCT / ORDER BY / SKIP post-processing, serialises the result
 *       list as synthetic {@code Entry<Key,Value>} entries, and wraps with
 *       LIMIT if requested.</li>
 *   <li>{@link #getTransformer}: returns a {@link CypherQueryTransformer}
 *       that projects RETURN columns from each {@link PathTuple}.</li>
 * </ol>
 *
 * <p>Production Spring wiring is deferred to M4.
 */
public class CypherQueryLogic extends BaseQueryLogic<Map.Entry<Key,Value>> {

    public static final String DEFAULT_SCHEMA_RESOURCE = "config/cypher-graph-schema.xml";

    private String graphSchemaResource = DEFAULT_SCHEMA_RESOURCE;
    private GraphSchema graphSchema;
    private int queryThreads = 8;
    private ShardEnrichmentService enrichmentService;

    // M3 executor knobs (mirrored onto each CypherQueryConfiguration in initialize()).
    private int maxVariableLengthUpper = ExecutorLimits.DEFAULTS.getMaxVariableLengthUpper();
    private int maxFrontierSize = ExecutorLimits.DEFAULTS.getMaxFrontierSize();
    private int maxAggregateGroups = ExecutorLimits.DEFAULTS.getMaxAggregateGroups();
    private int maxPathEdgeHistory = ExecutorLimits.DEFAULTS.getMaxPathEdgeHistory();

    private CypherQueryConfiguration activeConfig;

    public CypherQueryLogic() {
        super();
    }

    public CypherQueryLogic(CypherQueryLogic other) {
        super(other);
        this.graphSchemaResource = other.graphSchemaResource;
        this.graphSchema = other.graphSchema;
        this.queryThreads = other.queryThreads;
        this.enrichmentService = other.enrichmentService;
        this.maxVariableLengthUpper = other.maxVariableLengthUpper;
        this.maxFrontierSize = other.maxFrontierSize;
        this.maxAggregateGroups = other.maxAggregateGroups;
        this.maxPathEdgeHistory = other.maxPathEdgeHistory;
    }

    @Override
    public CypherQueryConfiguration initialize(AccumuloClient client, Query settings, Set<Authorizations> auths) throws Exception {
        if (graphSchema == null) {
            graphSchema = GraphSchemaLoader.load(graphSchemaResource);
        }
        String cypherText = settings.getQuery();
        if (cypherText == null || cypherText.isBlank()) {
            throw new IllegalArgumentException("Cypher query text was empty");
        }

        CypherFrontEnd.Analysis analysis = new CypherFrontEnd().analyze(cypherText);
        CypherPlanner planner = new CypherPlanner(graphSchema);
        // Reflect any per-logic var-length cap override into the planner before planning.
        planner.setMaxVariableLengthUpper(maxVariableLengthUpper);
        CypherPlan plan = planner.plan(analysis);

        // Pre-translate only the first hop, and only when it's fixed-length: a
        // variable-length first hop builds per-step translations from the literal
        // source-identity frontier inside the executor.
        HopTranslator translator = new HopTranslator();
        List<HopTranslation> translations = new ArrayList<>();
        if (!plan.getHops().isEmpty() && !plan.getHops().get(0).isVariableLength()) {
            translations.add(translator.translate(plan.getHops().get(0)));
        }

        CypherQueryConfiguration cfg = new CypherQueryConfiguration();
        cfg.copyFrom(super.getConfig());
        cfg.setClient(client);
        cfg.setQuery(settings);
        cfg.setQueryString(cypherText);
        cfg.setAuthorizations(auths);
        cfg.setBeginDate(settings.getBeginDate());
        cfg.setEndDate(settings.getEndDate());
        cfg.setTableName(getTableName());
        cfg.setCypherText(cypherText);
        cfg.setPlan(plan);
        cfg.setHopTranslations(translations);
        cfg.setMaxVariableLengthUpper(maxVariableLengthUpper);
        cfg.setMaxFrontierSize(maxFrontierSize);
        cfg.setMaxAggregateGroups(maxAggregateGroups);
        cfg.setMaxPathEdgeHistory(maxPathEdgeHistory);

        this.activeConfig = cfg;
        return cfg;
    }

    @Override
    public void setupQuery(GenericQueryConfiguration configuration) throws Exception {
        if (!(configuration instanceof CypherQueryConfiguration)) {
            throw new IllegalArgumentException("CypherQueryLogic requires a CypherQueryConfiguration");
        }
        CypherQueryConfiguration cfg = (CypherQueryConfiguration) configuration;
        this.activeConfig = cfg;

        AccumuloClient client = cfg.getClient();
        Set<Authorizations> auths = cfg.getAuthorizations();
        if (auths == null || auths.isEmpty()) {
            auths = Collections.singleton(Authorizations.EMPTY);
        }

        CypherPlan plan = cfg.getPlan();

        ExecutorLimits limits = new ExecutorLimits(cfg.getMaxVariableLengthUpper(), cfg.getMaxFrontierSize(), cfg.getMaxAggregateGroups(),
                        cfg.getMaxPathEdgeHistory());
        MultiHopExecutor executor = new MultiHopExecutor(client, auths, cfg.getTableName(), queryThreads, cfg.getQuery(),
                        new HopTranslator(), enrichmentService, limits);

        List<PathTuple> results = executor.execute(plan, cfg.getHopTranslations());
        cfg.setResultTuples(results);

        // Convert PathTuple list to synthetic Entry<Key,Value> iterator.
        List<Map.Entry<Key,Value>> entries = buildSyntheticEntries(results);
        Iterator<Map.Entry<Key,Value>> it = entries.iterator();

        if (plan.getLimitBoxed().isPresent()) {
            long lim = plan.getLimitBoxed().get();
            it = Iterators.limit(it, (int) Math.min(Integer.MAX_VALUE, lim));
        }

        this.iterator = it;
    }

    /**
     * Encodes each {@link PathTuple} as a synthetic {@code Entry<Key,Value>}
     * where the row key is the tuple index and the value carries the
     * serialized tuple bytes.
     */
    private List<Map.Entry<Key,Value>> buildSyntheticEntries(List<PathTuple> tuples) {
        List<Map.Entry<Key,Value>> out = new ArrayList<>(tuples.size());
        for (PathTuple t : tuples) {
            Key k = new Key(Integer.toString(out.size()));
            Value v = new Value(t.toBytes());
            out.add(new AbstractMap.SimpleImmutableEntry<>(k, v));
        }
        return out;
    }

    @Override
    public QueryLogicTransformer getTransformer(Query settings) {
        CypherQueryConfiguration cfg = this.activeConfig;
        if (cfg == null) {
            throw new IllegalStateException("getTransformer() called before initialize()/setupQuery()");
        }
        return new CypherQueryTransformer(settings, this.markingFunctions, cfg.getAuthorizations(), cfg.getPlan());
    }

    @Override
    public Priority getConnectionPriority() {
        return Priority.NORMAL;
    }

    @Override
    public CypherQueryLogic clone() {
        return new CypherQueryLogic(this);
    }

    @Override
    public String getPlan(AccumuloClient client, Query settings, Set<Authorizations> runtimeQueryAuthorizations, boolean expandFields, boolean expandValues)
                    throws Exception {
        CypherFrontEnd.Analysis analysis = new CypherFrontEnd().analyze(settings.getQuery());
        if (graphSchema == null) {
            graphSchema = GraphSchemaLoader.load(graphSchemaResource);
        }
        CypherPlanner planner = new CypherPlanner(graphSchema);
        planner.setMaxVariableLengthUpper(maxVariableLengthUpper);
        CypherPlan plan = planner.plan(analysis);
        HopTranslator translator = new HopTranslator();
        StringBuilder sb = new StringBuilder("Cypher plan: ").append(plan.getHops().size()).append(" hop(s)\n");
        for (int i = 0; i < plan.getHops().size(); i++) {
            HopSpec hop = plan.getHops().get(i);
            sb.append("  hop ").append(i).append(": ");
            if (hop.isVariableLength()) {
                sb.append("VAR_LENGTH [").append(hop.getLower().getAsInt()).append("..").append(hop.getUpper().getAsInt())
                                .append("] type=").append(hop.getRel().getCypherType())
                                .append(" dir=").append(hop.getPatternDirection())
                                .append(" (frontier-driven per step)");
                hop.getPathVariable().ifPresent(pv -> sb.append(" path=").append(pv));
            } else if (i == 0) {
                HopTranslation t = translator.translate(hop);
                sb.append("ranges=").append(t.getRanges()).append(" filter=").append(t.getFilterJexl()).append(" swap=").append(t.isSwappedEndpoints());
                hop.getPathVariable().ifPresent(pv -> sb.append(" path=").append(pv));
            } else {
                sb.append("frontier-translated at runtime type=").append(hop.getRel().getCypherType()).append(" dir=").append(hop.getPatternDirection());
                hop.getPathVariable().ifPresent(pv -> sb.append(" path=").append(pv));
            }
            sb.append('\n');
        }
        plan.getGroupingSpec().ifPresent(g -> {
            sb.append("  grouping: keys=").append(g.getGroupByKeys().size())
                            .append(" aggregates=[");
            for (int i = 0; i < g.getAggregates().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                Projection ap = g.getAggregates().get(i);
                sb.append(ap.getAlias()).append("=").append(ap.getAggregateSpec().map(s -> s.getFunc().name()).orElse("?"));
            }
            sb.append("]\n");
        });
        sb.append("  distinct=").append(plan.isDistinct()).append(" orderBy=").append(plan.getOrderBy().size()).append(" item(s)").append(" skip=")
                        .append(plan.getSkipBoxed().orElse(0L)).append(" limit=").append(plan.getLimitBoxed().orElse(null));
        return sb.toString();
    }

    @Override
    public Set<String> getRequiredQueryParameters() {
        return Collections.emptySet();
    }

    @Override
    public Set<String> getOptionalQueryParameters() {
        return Collections.emptySet();
    }

    @Override
    public Set<String> getExampleQueries() {
        Set<String> s = new HashSet<>();
        s.add("MATCH (a:Actor {name:'jerry seinfeld'})-[:COSTAR_OF]-(b:Actor) RETURN b.name AS costar LIMIT 10");
        s.add("MATCH (a:Actor {name:'jerry seinfeld'})-[:COSTAR_OF]-(b:Actor)-[:COSTAR_OF]-(c:Actor) RETURN DISTINCT c.name AS name");
        s.add("MATCH (a:Actor {name:'jerry seinfeld'})-[:COSTAR_OF*1..3]-(b:Actor) RETURN DISTINCT b.name AS reachable");
        s.add("MATCH (a:Actor)-[:COSTAR_OF]-(b:Actor) RETURN a.name AS actor, count(b) AS costars ORDER BY costars DESC LIMIT 5");
        s.add("MATCH p = (a:Actor {name:'jerry seinfeld'})-[:COSTAR_OF*1..2]-(b:Actor) RETURN p LIMIT 5");
        return s;
    }

    // ---- Spring setters -------------------------------------------------

    public String getGraphSchemaResource() {
        return graphSchemaResource;
    }

    public void setGraphSchemaResource(String graphSchemaResource) {
        this.graphSchemaResource = graphSchemaResource;
    }

    public GraphSchema getGraphSchema() {
        return graphSchema;
    }

    public void setGraphSchema(GraphSchema graphSchema) {
        this.graphSchema = graphSchema;
    }

    public int getQueryThreads() {
        return queryThreads;
    }

    public void setQueryThreads(int queryThreads) {
        this.queryThreads = queryThreads;
    }

    public ShardEnrichmentService getEnrichmentService() {
        return enrichmentService;
    }

    public void setEnrichmentService(ShardEnrichmentService enrichmentService) {
        this.enrichmentService = enrichmentService;
    }

    public int getMaxVariableLengthUpper() {
        return maxVariableLengthUpper;
    }

    public void setMaxVariableLengthUpper(int maxVariableLengthUpper) {
        this.maxVariableLengthUpper = maxVariableLengthUpper;
    }

    public int getMaxFrontierSize() {
        return maxFrontierSize;
    }

    public void setMaxFrontierSize(int maxFrontierSize) {
        this.maxFrontierSize = maxFrontierSize;
    }

    public int getMaxAggregateGroups() {
        return maxAggregateGroups;
    }

    public void setMaxAggregateGroups(int maxAggregateGroups) {
        this.maxAggregateGroups = maxAggregateGroups;
    }

    public int getMaxPathEdgeHistory() {
        return maxPathEdgeHistory;
    }

    public void setMaxPathEdgeHistory(int maxPathEdgeHistory) {
        this.maxPathEdgeHistory = maxPathEdgeHistory;
    }
}
