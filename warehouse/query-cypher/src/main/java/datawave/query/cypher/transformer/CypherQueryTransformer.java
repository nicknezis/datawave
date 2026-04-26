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
import org.apache.accumulo.core.security.ColumnVisibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import datawave.core.query.logic.BaseQueryLogicTransformer;
import datawave.edge.util.EdgeKey;
import datawave.edge.util.EdgeValue;
import datawave.marking.MarkingFunctions;
import datawave.microservice.query.Query;
import datawave.query.cypher.mapping.EdgeAttributeSlot;
import datawave.query.cypher.mapping.RelMapping;
import datawave.query.cypher.physical.HopTranslation;
import datawave.query.cypher.planner.CypherPlan;
import datawave.query.cypher.planner.HopSpec;
import datawave.query.cypher.planner.NodeBinding;
import datawave.query.cypher.planner.Projection;

/**
 * Maps each edge {@code (Key, Value)} returned from the
 * {@link datawave.query.iterator.filter.EdgeFilterIterator}-filtered scan
 * into a {@link CypherRow} carrying the RETURN-clause projections, with
 * column-visibility markings translated through {@link MarkingFunctions}
 * exactly as {@code EdgeQueryTransformer} does.
 *
 * <p>Honors the {@link HopTranslation#isSwappedEndpoints() endpoint swap}
 * the translator may have applied to take advantage of bidirectional edge
 * storage: when set, the row's SOURCE on disk maps to the user's sink
 * variable and vice versa.
 */
public class CypherQueryTransformer extends BaseQueryLogicTransformer<Entry<Key,Value>,CypherRow> {

    private static final Logger log = LoggerFactory.getLogger(CypherQueryTransformer.class);

    private final Query settings;
    private final Set<Authorizations> auths;
    private final CypherPlan plan;
    private final HopTranslation translation;

    public CypherQueryTransformer(Query settings, MarkingFunctions markingFunctions, Set<Authorizations> auths, CypherPlan plan, HopTranslation translation) {
        super(markingFunctions);
        this.settings = settings;
        this.auths = auths;
        this.plan = Objects.requireNonNull(plan, "plan");
        this.translation = Objects.requireNonNull(translation, "translation");
    }

    @Override
    public CypherRow transform(Entry<Key,Value> entry) {
        EdgeKey edgeKey = EdgeKey.decode(entry.getKey());
        String diskSource = edgeKey.getSourceData();
        String diskSink = edgeKey.getSinkData();

        // Prefer un-normalized values from the protobuf payload when present,
        // mirroring EdgeQueryTransformer's behavior.
        try {
            EdgeValue ev = EdgeValue.decode(entry.getValue());
            if (ev.getSourceValue() != null) {
                diskSource = ev.getSourceValue();
            }
            if (ev.getSinkValue() != null) {
                diskSink = ev.getSinkValue();
            }
        } catch (Exception e) {
            log.debug("Edge protobuf decode skipped, falling back to row-key endpoints: {}", e.getMessage());
        }

        HopSpec hop = plan.getHop();
        NodeBinding sourceVarBinding = hop.getSource();
        NodeBinding sinkVarBinding = hop.getSink();
        String sourceVarValue;
        String sinkVarValue;
        if (translation.isSwappedEndpoints()) {
            // Translator swapped at scan time; un-swap so the user's
            // variables bind to the on-disk endpoint they originally
            // identified.
            sourceVarValue = diskSink;
            sinkVarValue = diskSource;
        } else {
            sourceVarValue = diskSource;
            sinkVarValue = diskSink;
        }

        Map<String,String> markings;
        try {
            markings = markingFunctions.translateFromColumnVisibilityForAuths(new ColumnVisibility(edgeKey.getColvis()), auths);
        } catch (Exception ex) {
            log.warn("could not translate column visibility {}: {}", edgeKey.getColvis(), ex.toString());
            markings = new LinkedHashMap<>();
        }

        Map<String,String> columns = new LinkedHashMap<>();
        for (Projection proj : plan.getProjections()) {
            String value = projectValue(proj, hop, sourceVarBinding, sinkVarBinding, sourceVarValue, sinkVarValue, edgeKey);
            columns.put(proj.getAlias(), value);
        }
        return new CypherRow(columns, markings);
    }

    private String projectValue(Projection proj, HopSpec hop, NodeBinding sourceVarBinding, NodeBinding sinkVarBinding, String sourceVarValue,
                    String sinkVarValue, EdgeKey edgeKey) {
        if (proj.getKind() == Projection.Kind.NODE_PROPERTY) {
            if (proj.getVariable().equals(sourceVarBinding.getVariable())) {
                return sourceVarValue;
            }
            if (proj.getVariable().equals(sinkVarBinding.getVariable())) {
                return sinkVarValue;
            }
            throw new IllegalStateException("projection variable not bound: " + proj.getVariable());
        }
        // REL_PROPERTY
        RelMapping rel = hop.getRel();
        EdgeAttributeSlot slot = rel.resolveAttributeSlot(proj.getProperty())
                        .orElseThrow(() -> new IllegalStateException("projection rel-property not in schema: " + proj.getProperty()));
        switch (slot) {
            case ATTRIBUTE1:
                return edgeKey.getAttribute1();
            case ATTRIBUTE2:
                return edgeKey.getAttribute2();
            case ATTRIBUTE3:
                return edgeKey.getAttribute3();
            default:
                throw new IllegalStateException("unexpected slot " + slot);
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
