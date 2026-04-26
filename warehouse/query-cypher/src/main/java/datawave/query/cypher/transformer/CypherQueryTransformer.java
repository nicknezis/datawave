package datawave.query.cypher.transformer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import datawave.core.query.logic.BaseQueryLogicTransformer;
import datawave.marking.MarkingFunctions;
import datawave.microservice.query.Query;
import datawave.query.cypher.executor.PathTuple;
import datawave.query.cypher.planner.CypherPlan;
import datawave.query.cypher.planner.Projection;

/**
 * Projects RETURN columns from each synthetic {@code Entry<Key,Value>} that
 * encodes a {@link PathTuple} (produced by {@link datawave.query.cypher.executor.MultiHopExecutor}).
 *
 * <p>The Value bytes hold the {@link PathTuple} serialization;
 * {@link #transform} decodes them and maps the RETURN projections.
 * Column-visibility markings are not available for synthetic entries
 * (multi-hop paths compose visibilities from multiple source cells); M4
 * will wire MarkingFunctions across the path chain.
 */
public class CypherQueryTransformer extends BaseQueryLogicTransformer<Entry<Key,Value>,CypherRow> {

    private static final Logger log = LoggerFactory.getLogger(CypherQueryTransformer.class);

    private final Query settings;
    private final Set<Authorizations> auths;
    private final CypherPlan plan;

    public CypherQueryTransformer(Query settings, MarkingFunctions markingFunctions, Set<Authorizations> auths, CypherPlan plan) {
        super(markingFunctions);
        this.settings = settings;
        this.auths = auths;
        this.plan = Objects.requireNonNull(plan, "plan");
    }

    @Override
    public CypherRow transform(Entry<Key,Value> entry) {
        PathTuple tuple = PathTuple.fromBytes(entry.getValue().get());

        Map<String,String> columns = new LinkedHashMap<>();
        for (Projection proj : plan.getProjections()) {
            columns.put(proj.getAlias(), resolveValue(tuple, proj));
        }

        // Visibility markings: multi-hop path composed visibility is deferred to M4.
        Map<String,String> markings = new LinkedHashMap<>();

        return new CypherRow(columns, markings);
    }

    private String resolveValue(PathTuple tuple, Projection proj) {
        switch (proj.getKind()) {
            case NODE_PROPERTY:
                return tuple.get(proj.getVariable());
            case REL_PROPERTY:
            case NODE_SHARD_PROPERTY:
                return tuple.get(proj.getVariable() + "." + proj.getProperty());
            default:
                log.warn("unknown projection kind {}", proj.getKind());
                return null;
        }
    }

    @Override
    public CypherQueryResponse createResponse(List<Object> resultList) {
        CypherQueryResponse response = new CypherQueryResponse();
        if (settings != null) {
            response.setQueryId(settings.getId() == null ? null : settings.getId().toString());
        }
        List<String> columns = new ArrayList<>();
        for (Projection p : plan.getProjections()) {
            columns.add(p.getAlias());
        }
        response.setColumns(columns);
        List<CypherRow> rows = new ArrayList<>(resultList.size());
        for (Object o : resultList) {
            rows.add((CypherRow) o);
        }
        response.setRows(rows);
        return response;
    }
}
