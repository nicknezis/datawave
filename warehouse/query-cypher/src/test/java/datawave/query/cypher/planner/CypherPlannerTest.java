package datawave.query.cypher.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import datawave.query.cypher.CypherFrontEnd;
import datawave.query.cypher.ast.RelationshipPattern;
import datawave.query.cypher.mapping.EdgeAttributeSlot;
import datawave.query.cypher.mapping.EdgeDirection;
import datawave.query.cypher.mapping.GraphSchema;
import datawave.query.cypher.mapping.NodeMapping;
import datawave.query.cypher.mapping.RelMapping;

public class CypherPlannerTest {

    private GraphSchema schema;
    private CypherPlanner planner;
    private final CypherFrontEnd frontEnd = new CypherFrontEnd();

    @Before
    public void setUp() {
        Map<String,String> actorProps = new LinkedHashMap<>();
        actorProps.put("name", "EMBEDDED_CAST_PERSON_NAME");
        Map<String,EdgeAttributeSlot> attrs = new LinkedHashMap<>();
        attrs.put("show", EdgeAttributeSlot.ATTRIBUTE2);
        attrs.put("showId", EdgeAttributeSlot.ATTRIBUTE3);
        NodeMapping actor = new NodeMapping("Actor", "tvmaze", "name", "EMBEDDED_CAST_PERSON_NAME", actorProps);
        RelMapping costar = new RelMapping("COSTAR_OF", "TV_COSTARS", "Actor", "Actor", EdgeDirection.UNDIRECTED, attrs);
        schema = GraphSchema.of(List.of(actor), List.of(costar));
        planner = new CypherPlanner(schema);
    }

    @Test
    public void plansSingleHopWithIdentityFilter() {
        CypherPlan plan = planner.plan(frontEnd
                        .analyze("MATCH (a:Actor {name:'jerry seinfeld'})-[:COSTAR_OF]-(b:Actor) RETURN b.name AS costar LIMIT 10"));

        HopSpec hop = plan.getHop();
        assertThat(hop.getSource().getVariable()).isEqualTo("a");
        assertThat(hop.getSink().getVariable()).isEqualTo("b");
        assertThat(hop.getSource().getIdentityEquals()).containsEntry("name", "jerry seinfeld");
        assertThat(hop.getSink().hasIdentityFilter()).isFalse();
        assertThat(hop.getRel().getEdgeType()).isEqualTo("TV_COSTARS");
        assertThat(hop.getPatternDirection()).isEqualTo(RelationshipPattern.Direction.UNDIRECTED);

        assertThat(plan.getProjections()).hasSize(1);
        Projection p = plan.getProjections().get(0);
        assertThat(p.getAlias()).isEqualTo("costar");
        assertThat(p.getVariable()).isEqualTo("b");
        assertThat(p.getProperty()).isEqualTo("name");
        assertThat(p.getKind()).isEqualTo(Projection.Kind.NODE_PROPERTY);

        assertThat(plan.getLimitBoxed()).contains(10L);
    }

    @Test
    public void plansBothSidesWithIdentityFilters() {
        CypherPlan plan = planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor)-[:COSTAR_OF]-(b:Actor) WHERE a.name = 'jerry seinfeld' AND b.name = 'kramer' RETURN a.name AS x, b.name AS y"));
        assertThat(plan.getHop().getSource().getIdentityEquals()).containsEntry("name", "jerry seinfeld");
        assertThat(plan.getHop().getSink().getIdentityEquals()).containsEntry("name", "kramer");
        assertThat(plan.getProjections()).extracting(Projection::getAlias).containsExactly("x", "y");
    }

    @Test
    public void plansEdgePropertyFilterAndProjection() {
        CypherPlan plan = planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor {name:'jerry seinfeld'})-[r:COSTAR_OF]-(b:Actor) WHERE r.show = 'Seinfeld' RETURN b.name AS costar, r.show AS show"));
        assertThat(plan.getHop().getAttributeEquals()).containsEntry("show", "Seinfeld");
        assertThat(plan.getProjections()).extracting(Projection::getAlias).containsExactly("costar", "show");
        Projection showProj = plan.getProjections().get(1);
        assertThat(showProj.getKind()).isEqualTo(Projection.Kind.REL_PROPERTY);
    }

    @Test
    public void rejectsAggregationProjection() {
        assertThatThrownBy(() -> planner.plan(frontEnd
                        .analyze("MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]-(b:Actor) RETURN count(b) AS n"))).isInstanceOf(CypherUnsupportedException.class)
                                        .hasMessageContaining("property projections");
    }

    @Test
    public void rejectsOrderBy() {
        assertThatThrownBy(() -> planner.plan(frontEnd
                        .analyze("MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]-(b:Actor) RETURN b.name AS x ORDER BY x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("ORDER BY");
    }

    @Test
    public void rejectsDistinct() {
        assertThatThrownBy(() -> planner.plan(
                        frontEnd.analyze("MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]-(b:Actor) RETURN DISTINCT b.name AS x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("DISTINCT");
    }

    @Test
    public void rejectsVariableLengthRelationship() {
        assertThatThrownBy(() -> planner.plan(frontEnd
                        .analyze("MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF*1..3]-(b:Actor) RETURN b.name AS x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("variable-length");
    }

    @Test
    public void rejectsMultiHop() {
        assertThatThrownBy(() -> planner.plan(frontEnd
                        .analyze("MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]-(b:Actor)-[:COSTAR_OF]-(c:Actor) RETURN c.name AS x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("multi-hop");
    }

    @Test
    public void rejectsOptionalMatch() {
        assertThatThrownBy(() -> planner.plan(frontEnd
                        .analyze("OPTIONAL MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]-(b:Actor) RETURN b.name AS x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("OPTIONAL MATCH");
    }

    @Test
    public void rejectsUnknownLabel() {
        assertThatThrownBy(() -> planner.plan(frontEnd
                        .analyze("MATCH (a:Director {name:'jerry'})-[:COSTAR_OF]-(b:Actor) RETURN b.name AS x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("Director");
    }

    @Test
    public void rejectsUnknownRelationshipType() {
        assertThatThrownBy(() -> planner.plan(
                        frontEnd.analyze("MATCH (a:Actor {name:'jerry'})-[:DIRECTED]-(b:Actor) RETURN b.name AS x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("DIRECTED");
    }

    @Test
    public void rejectsNonIdentityNodePropertyInWhere() {
        assertThatThrownBy(() -> planner.plan(frontEnd
                        .analyze("MATCH (a:Actor)-[:COSTAR_OF]-(b:Actor) WHERE a.id = '12345' RETURN b.name AS x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("M2");
    }

    @Test
    public void rejectsNonIdentityNodePropertyInReturn() {
        assertThatThrownBy(() -> planner.plan(frontEnd
                        .analyze("MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]-(b:Actor) RETURN b.id AS x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("M2");
    }

    @Test
    public void rejectsOrInWhere() {
        assertThatThrownBy(() -> planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor)-[:COSTAR_OF]-(b:Actor) WHERE a.name = 'jerry' OR a.name = 'kramer' RETURN b.name AS x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("OR");
    }

    @Test
    public void rejectsWith() {
        assertThatThrownBy(() -> planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]-(b:Actor) WITH b RETURN b.name AS x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("WITH");
    }

    @Test
    public void rejectsPathBinding() {
        assertThatThrownBy(() -> planner.plan(frontEnd
                        .analyze("MATCH p = (a:Actor {name:'jerry'})-[:COSTAR_OF]-(b:Actor) RETURN b.name AS x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("path variable");
    }

    @Test
    public void rejectsAnonymousNodeWithoutVariable() {
        assertThatThrownBy(() -> planner.plan(
                        frontEnd.analyze("MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]-(:Actor) RETURN a.name AS x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("anonymous");
    }

    @Test
    public void smokeTestOutgoingDirection() {
        // Schema source/sink are both Actor, so OUTGOING is structurally valid.
        CypherPlan plan = planner.plan(frontEnd
                        .analyze("MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]->(b:Actor) RETURN b.name AS x"));
        assertThat(plan.getHop().getPatternDirection()).isEqualTo(RelationshipPattern.Direction.OUTGOING);
    }
}
