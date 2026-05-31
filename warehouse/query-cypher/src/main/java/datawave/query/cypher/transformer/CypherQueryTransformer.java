package datawave.query.cypher.transformer;

import java.math.BigDecimal;
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
import org.apache.accumulo.core.security.ColumnVisibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import datawave.core.query.logic.BaseQueryLogicTransformer;
import datawave.marking.MarkingFunctions;
import datawave.microservice.query.Query;
import datawave.query.cypher.executor.PathElement;
import datawave.query.cypher.executor.PathTuple;
import datawave.query.cypher.planner.AggregateSpec;
import datawave.query.cypher.planner.CypherPlan;
import datawave.query.cypher.planner.Projection;

/**
 * Projects RETURN columns from each synthetic {@code Entry<Key,Value>} that encodes a {@link PathTuple} (produced by
 * {@link datawave.query.cypher.executor.MultiHopExecutor}).
 *
 * <p>
 * M3: column values are typed ({@link CypherValue}); path projections materialise as nested {@link CypherValue.PathValue} records; aggregate results read from
 * the executor's {@code __agg__.<alias>} reserved keys and adopt the right scalar type per function. Composite-row markings are the AND of every contributing
 * cell's {@link ColumnVisibility} via {@link MarkingFunctions#combine}.
 */
public class CypherQueryTransformer extends BaseQueryLogicTransformer<Entry<Key,Value>,CypherRow> {

    private static final Logger log = LoggerFactory.getLogger(CypherQueryTransformer.class);

    private final Query settings;
    private final Set<Authorizations> auths;
    private final CypherPlan plan;
    private final MarkingFunctions markingFunctions;

    public CypherQueryTransformer(Query settings, MarkingFunctions markingFunctions, Set<Authorizations> auths, CypherPlan plan) {
        super(markingFunctions);
        this.settings = settings;
        this.markingFunctions = markingFunctions;
        this.auths = auths;
        this.plan = Objects.requireNonNull(plan, "plan");
    }

    @Override
    public CypherRow transform(Entry<Key,Value> entry) {
        PathTuple tuple = PathTuple.fromBytes(entry.getValue().get());

        Map<String,CypherValue> columns = new LinkedHashMap<>();
        for (Projection proj : plan.getProjections()) {
            columns.put(proj.getAlias(), resolveValue(tuple, proj));
        }

        return new CypherRow(columns, composeMarkings(tuple));
    }

    private CypherValue resolveValue(PathTuple tuple, Projection proj) {
        switch (proj.getKind()) {
            case NODE_PROPERTY:
                return wrapString(tuple.get(proj.getVariable()));
            case REL_PROPERTY:
            case NODE_SHARD_PROPERTY:
                return wrapString(tuple.get(proj.getVariable() + "." + proj.getProperty()));
            case PATH_OBJECT:
                return buildPathValue(tuple, proj.getVariable());
            case AGGREGATE:
                return buildAggregateValue(tuple, proj);
            default:
                log.warn("unknown projection kind {}", proj.getKind());
                return CypherValue.string(null);
        }
    }

    private static CypherValue wrapString(String value) {
        return CypherValue.string(value);
    }

    private CypherValue buildPathValue(PathTuple tuple, String pathVar) {
        List<PathElement> elements = tuple.getPath(pathVar);
        List<PathRecord> records = new ArrayList<>(elements.size());
        for (PathElement el : elements) {
            if (el.getKind() == PathElement.Kind.NODE) {
                PathElement.NodeElement n = (PathElement.NodeElement) el;
                records.add(PathRecord.node(n.getVariable(), n.getIdentity(), n.getProperties()));
            } else {
                PathElement.EdgeElement e = (PathElement.EdgeElement) el;
                records.add(PathRecord.edge(e.getVariable(), e.getType(), e.getSourceIdentity(), e.getSinkIdentity(), e.getAttributes()));
            }
        }
        return CypherValue.path(records);
    }

    private CypherValue buildAggregateValue(PathTuple tuple, Projection proj) {
        String raw = tuple.get(Projection.AGGREGATE_TUPLE_KEY_PREFIX + proj.getAlias());
        AggregateSpec spec = proj.getAggregateSpec().orElse(null);
        if (spec == null) {
            return CypherValue.string(raw);
        }
        switch (spec.getFunc()) {
            case COUNT_STAR:
            case COUNT:
                if (raw == null) {
                    return CypherValue.longValue(0L);
                }
                try {
                    return CypherValue.longValue(Long.parseLong(raw));
                } catch (NumberFormatException e) {
                    // Upstream produced a non-numeric value for a COUNT column — that
                    // can only happen via aggregator/alias-key drift. Surface the raw
                    // value to the caller (as a string) and log instead of silently
                    // emitting zero, which would silently corrupt downstream totals.
                    log.warn("COUNT projection '{}' received non-numeric value '{}'; emitting raw string to avoid silent zero", proj.getAlias(), raw);
                    return CypherValue.string(raw);
                }
            case SUM:
            case AVG:
                if (raw == null) {
                    return CypherValue.string(null);
                }
                try {
                    return CypherValue.decimal(new BigDecimal(raw));
                } catch (NumberFormatException e) {
                    return CypherValue.string(raw);
                }
            case MIN:
            case MAX:
            default:
                return CypherValue.string(raw);
        }
    }

    private Map<String,String> composeMarkings(PathTuple tuple) {
        List<ColumnVisibility> visibilities = tuple.getVisibilities();
        if (visibilities.isEmpty() || markingFunctions == null) {
            return new LinkedHashMap<>();
        }
        try {
            ColumnVisibility combined = markingFunctions.combine(visibilities);
            return markingFunctions.translateFromColumnVisibilityForAuths(combined, auths);
        } catch (MarkingFunctions.Exception e) {
            log.warn("failed to combine path visibilities; emitting empty markings: {}", e.getMessage());
            return new LinkedHashMap<>();
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
