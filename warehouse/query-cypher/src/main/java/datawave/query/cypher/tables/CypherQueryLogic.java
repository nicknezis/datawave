package datawave.query.cypher.tables;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.IteratorSetting;
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
import datawave.query.cypher.mapping.GraphSchema;
import datawave.query.cypher.mapping.GraphSchemaLoader;
import datawave.query.cypher.physical.HopTranslation;
import datawave.query.cypher.physical.HopTranslator;
import datawave.query.cypher.planner.CypherPlan;
import datawave.query.cypher.planner.CypherPlanner;
import datawave.query.cypher.transformer.CypherQueryTransformer;
import datawave.query.cypher.transformer.CypherRow;
import datawave.query.iterator.filter.EdgeFilterIterator;
import datawave.query.util.QueryScannerHelper;

/**
 * The M1 single-hop Cypher query logic. Bridges the Cypher front-end
 * (parser + AST + semantic analyzer) and planner with DataWave's
 * {@link BaseQueryLogic} lifecycle.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #initialize}: parses the Cypher text, runs the planner and
 *       hop translator against the configured {@link GraphSchema}, and
 *       stashes the plan + translation in the returned
 *       {@link CypherQueryConfiguration}.</li>
 *   <li>{@link #setupQuery}: opens a BatchScanner over the edge table with
 *       the translated ranges, attaches an
 *       {@link EdgeFilterIterator} carrying the synthesized JEXL filter,
 *       and (if the plan has a LIMIT) bounds the result iterator.</li>
 *   <li>{@link #getTransformer}: returns a
 *       {@link CypherQueryTransformer} that materializes
 *       {@link CypherRow rows} from each edge cell.</li>
 * </ol>
 *
 * <p>Production Spring wiring (a {@code CypherQuery} bean in
 * {@code QueryLogicFactory.xml}) is intentionally deferred to M4 along
 * with the rest of the production-readiness work; M1 wires this logic
 * directly in test/demo code.
 */
public class CypherQueryLogic extends BaseQueryLogic<Map.Entry<Key,Value>> {

    /** Where the Spring XML graph-schema descriptor lives on the classpath. */
    public static final String DEFAULT_SCHEMA_RESOURCE = "config/cypher-graph-schema.xml";

    private static final int FILTER_PRIORITY = 130;

    private String graphSchemaResource = DEFAULT_SCHEMA_RESOURCE;
    private GraphSchema graphSchema;
    private int queryThreads = 8;

    /**
     * Typed view of the active query configuration. Populated by
     * {@link #initialize}; consulted by {@link #setupQuery} and
     * {@link #getTransformer}. BaseQueryLogic's {@link #getConfig()} returns
     * the {@link GenericQueryConfiguration} slice; the typed fields
     * (plan, hop translation) live here.
     */
    private CypherQueryConfiguration activeConfig;

    public CypherQueryLogic() {
        super();
    }

    public CypherQueryLogic(CypherQueryLogic other) {
        super(other);
        this.graphSchemaResource = other.graphSchemaResource;
        this.graphSchema = other.graphSchema;
        this.queryThreads = other.queryThreads;
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
        CypherPlan plan = new CypherPlanner(graphSchema).plan(analysis);
        HopTranslation translation = new HopTranslator().translate(plan.getHop());

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
        cfg.setHopTranslation(translation);

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

        BatchScanner batchScanner = QueryScannerHelper.createBatchScanner(client, cfg.getTableName(), auths, queryThreads, cfg.getQuery());
        batchScanner.setRanges(cfg.getHopTranslation().getRanges());

        IteratorSetting filter = new IteratorSetting(FILTER_PRIORITY, EdgeFilterIterator.class.getSimpleName() + "_" + FILTER_PRIORITY,
                        EdgeFilterIterator.class);
        filter.addOption(EdgeFilterIterator.JEXL_OPTION, cfg.getHopTranslation().getFilterJexl());
        filter.addOption(EdgeFilterIterator.PROTOBUF_OPTION, "TRUE");
        filter.addOption(EdgeFilterIterator.INCLUDE_STATS_OPTION, "FALSE");
        batchScanner.addScanIterator(filter);

        this.scanner = batchScanner;
        Iterator<Map.Entry<Key,Value>> it = batchScanner.iterator();
        if (cfg.getPlan().getLimitBoxed().isPresent()) {
            long lim = cfg.getPlan().getLimitBoxed().get();
            it = Iterators.limit(it, (int) Math.min(Integer.MAX_VALUE, lim));
        }
        this.iterator = it;
    }

    @Override
    public QueryLogicTransformer getTransformer(Query settings) {
        CypherQueryConfiguration cfg = this.activeConfig;
        if (cfg == null) {
            throw new IllegalStateException("getTransformer() called before initialize()/setupQuery()");
        }
        return new CypherQueryTransformer(settings, this.markingFunctions, cfg.getAuthorizations(), cfg.getPlan(), cfg.getHopTranslation());
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
        CypherPlan plan = new CypherPlanner(graphSchema).plan(analysis);
        HopTranslation translation = new HopTranslator().translate(plan.getHop());
        return "Cypher hop ranges=" + translation.getRanges() + " filter=" + translation.getFilterJexl()
                        + " swap=" + translation.isSwappedEndpoints();
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
        return s;
    }

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
}
