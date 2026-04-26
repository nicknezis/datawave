package datawave.query.cypher.physical;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.accumulo.core.data.Range;
import org.junit.Before;
import org.junit.Test;

import datawave.query.cypher.CypherFrontEnd;
import datawave.query.cypher.mapping.EdgeAttributeSlot;
import datawave.query.cypher.mapping.EdgeDirection;
import datawave.query.cypher.mapping.GraphSchema;
import datawave.query.cypher.mapping.NodeMapping;
import datawave.query.cypher.mapping.RelMapping;
import datawave.query.cypher.planner.CypherPlanner;

public class HopTranslatorTest {

    private CypherPlanner planner;
    private final CypherFrontEnd frontEnd = new CypherFrontEnd();
    private final HopTranslator translator = new HopTranslator();

    @Before
    public void setUp() {
        Map<String,String> actorProps = new LinkedHashMap<>();
        actorProps.put("name", "EMBEDDED_CAST_PERSON_NAME");
        Map<String,EdgeAttributeSlot> attrs = new LinkedHashMap<>();
        attrs.put("show", EdgeAttributeSlot.ATTRIBUTE2);
        NodeMapping actor = new NodeMapping("Actor", "tvmaze", "name", "EMBEDDED_CAST_PERSON_NAME", actorProps);
        RelMapping costar = new RelMapping("COSTAR_OF", "TV_COSTARS", "Actor", "Actor", EdgeDirection.UNDIRECTED, attrs);
        GraphSchema schema = GraphSchema.of(List.of(actor), List.of(costar));
        planner = new CypherPlanner(schema);
    }

    @Test
    public void sourceOnlyFilterBuildsRowPrefixRange() {
        HopTranslation t = translator.translate(planner
                        .plan(frontEnd.analyze("MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]-(b:Actor) RETURN b.name AS x"))
                        .getHop());
        assertThat(t.isSwappedEndpoints()).isFalse();
        assertThat(t.getFilterJexl()).isEqualTo("EDGE_TYPE == 'tv_costars' && EDGE_SOURCE == 'jerry'");
        assertThat(t.getRanges()).hasSize(1);
        Range r = t.getRanges().iterator().next();
        assertThat(r.getStartKey().getRow().toString()).isEqualTo("jerry\u0000");
        assertThat(r.getEndKey().getRow().toString()).isEqualTo("jerry\u0001");
    }

    @Test
    public void bothEndpointsFilteredBuildsExactRowRange() {
        HopTranslation t = translator.translate(planner.plan(frontEnd
                        .analyze("MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]-(b:Actor {name:'kramer'}) RETURN b.name AS x"))
                        .getHop());
        Range r = t.getRanges().iterator().next();
        assertThat(r.getStartKey().getRow().toString()).isEqualTo("jerry\u0000kramer");
        assertThat(r.getEndKey().getRow().toString()).isEqualTo("jerry\u0000kramer\u0000");
        assertThat(t.getFilterJexl()).contains("EDGE_SOURCE == 'jerry'").contains("EDGE_SINK == 'kramer'").contains("EDGE_TYPE == 'tv_costars'");
    }

    @Test
    public void edgeAttributeFilterAppearsInJexl() {
        HopTranslation t = translator.translate(planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor {name:'jerry'})-[r:COSTAR_OF]-(b:Actor) WHERE r.show = 'Seinfeld' RETURN b.name AS x"))
                        .getHop());
        assertThat(t.getFilterJexl()).contains("EDGE_ATTRIBUTE2 == 'seinfeld'");
    }

    @Test
    public void undirectedSinkOnlyFilterPivotsToSourceForEfficientScan() {
        HopTranslation t = translator.translate(planner.plan(frontEnd
                        .analyze("MATCH (a:Actor)-[:COSTAR_OF]-(b:Actor {name:'kramer'}) RETURN a.name AS x"))
                        .getHop());
        assertThat(t.isSwappedEndpoints()).isTrue();
        assertThat(t.getFilterJexl()).contains("EDGE_SOURCE == 'kramer'");
        Range r = t.getRanges().iterator().next();
        assertThat(r.getStartKey().getRow().toString()).isEqualTo("kramer\u0000");
    }

    @Test
    public void escapesQuotesInLiteralValues() {
        HopTranslation t = translator.translate(planner.plan(frontEnd
                        .analyze("MATCH (a:Actor {name:\"o'brien\"})-[:COSTAR_OF]-(b:Actor) RETURN b.name AS x"))
                        .getHop());
        assertThat(t.getFilterJexl()).contains("EDGE_SOURCE == 'o\\'brien'");
    }
}
