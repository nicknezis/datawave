package datawave.query.cypher.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import datawave.query.cypher.CypherFrontEnd;
import datawave.query.cypher.ast.RelationshipPattern;
import datawave.query.cypher.ast.SortItem;
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
        actorProps.put("id", "EMBEDDED_CAST_PERSON_ID");   // non-identity property for M2 tests
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

    // M2 used to reject aggregation; M3 accepts it — covered by acceptsAggregateProjection
    // below.

    // ---- M2: multi-hop -------------------------------------------------------

    @Test
    public void plansTwoHopInlinePattern() {
        CypherPlan plan = planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor {name:'jerry seinfeld'})-[:COSTAR_OF]-(b:Actor)-[:COSTAR_OF]-(c:Actor) RETURN c.name AS name"));

        assertThat(plan.getHops()).hasSize(2);

        HopSpec hop0 = plan.getHops().get(0);
        assertThat(hop0.getSource().getVariable()).isEqualTo("a");
        assertThat(hop0.getSink().getVariable()).isEqualTo("b");
        assertThat(hop0.getSource().getIdentityEquals()).containsEntry("name", "jerry seinfeld");

        HopSpec hop1 = plan.getHops().get(1);
        assertThat(hop1.getSource().getVariable()).isEqualTo("b");
        assertThat(hop1.getSink().getVariable()).isEqualTo("c");

        assertThat(plan.getProjections()).hasSize(1);
        assertThat(plan.getProjections().get(0).getAlias()).isEqualTo("name");
        assertThat(plan.getProjections().get(0).getVariable()).isEqualTo("c");
    }

    @Test
    public void plansThreeHopInlinePattern() {
        CypherPlan plan = planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]-(b:Actor)-[:COSTAR_OF]-(c:Actor)-[:COSTAR_OF]-(d:Actor) RETURN d.name AS name"));

        assertThat(plan.getHops()).hasSize(3);
        assertThat(plan.getHops().get(0).getSource().getVariable()).isEqualTo("a");
        assertThat(plan.getHops().get(1).getSource().getVariable()).isEqualTo("b");
        assertThat(plan.getHops().get(2).getSource().getVariable()).isEqualTo("c");
        assertThat(plan.getHops().get(2).getSink().getVariable()).isEqualTo("d");
    }

    // ---- M2: WITH ------------------------------------------------------------

    @Test
    public void plansWithChainedMatch() {
        CypherPlan plan = planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor {name:'jerry seinfeld'})-[:COSTAR_OF]-(b:Actor) WITH b MATCH (b)-[:COSTAR_OF]-(c:Actor) RETURN c.name AS name"));

        assertThat(plan.getHops()).hasSize(2);
        assertThat(plan.getHops().get(0).getSource().getVariable()).isEqualTo("a");
        assertThat(plan.getHops().get(0).getSink().getVariable()).isEqualTo("b");
        assertThat(plan.getHops().get(1).getSource().getVariable()).isEqualTo("b");
        assertThat(plan.getHops().get(1).getSink().getVariable()).isEqualTo("c");
    }

    // ---- M2: ORDER BY / SKIP / DISTINCT / RETURN * --------------------------

    @Test
    public void plansOrderByAsc() {
        CypherPlan plan = planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]-(b:Actor) RETURN b.name AS costar ORDER BY costar ASC"));

        assertThat(plan.getOrderBy()).hasSize(1);
        assertThat(plan.getOrderBy().get(0).getAlias()).isEqualTo("costar");
        assertThat(plan.getOrderBy().get(0).getDirection()).isEqualTo(SortItem.Direction.ASC);
        assertThat(plan.isDistinct()).isFalse();
        assertThat(plan.getSkipBoxed()).isEmpty();
    }

    @Test
    public void plansOrderByDescWithSkipAndLimit() {
        CypherPlan plan = planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]-(b:Actor) RETURN b.name AS n ORDER BY n DESC SKIP 5 LIMIT 10"));

        assertThat(plan.getOrderBy()).hasSize(1);
        assertThat(plan.getOrderBy().get(0).getDirection()).isEqualTo(SortItem.Direction.DESC);
        assertThat(plan.getSkipBoxed()).contains(5L);
        assertThat(plan.getLimitBoxed()).contains(10L);
    }

    @Test
    public void plansDistinct() {
        CypherPlan plan = planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]-(b:Actor)-[:COSTAR_OF]-(c:Actor) RETURN DISTINCT c.name AS name"));

        assertThat(plan.isDistinct()).isTrue();
        assertThat(plan.getProjections()).hasSize(1);
    }

    @Test
    public void plansReturnStar() {
        CypherPlan plan = planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]-(b:Actor) RETURN *"));

        // RETURN * expands to all bound node identity projections: a and b
        assertThat(plan.getProjections()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(plan.getProjections()).extracting(Projection::getVariable).contains("a", "b");
    }

    // ---- M2: non-identity node properties ------------------------------------

    @Test
    public void plansNonIdentityNodePropertyInReturn() {
        CypherPlan plan = planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]-(b:Actor) RETURN b.id AS x"));

        Projection p = plan.getProjections().get(0);
        assertThat(p.getAlias()).isEqualTo("x");
        assertThat(p.getVariable()).isEqualTo("b");
        assertThat(p.getProperty()).isEqualTo("id");
        assertThat(p.getKind()).isEqualTo(Projection.Kind.NODE_SHARD_PROPERTY);
    }

    @Test
    public void plansNonIdentityNodePropertyInWhere() {
        CypherPlan plan = planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor)-[:COSTAR_OF]-(b:Actor) WHERE a.name = 'jerry' AND b.id = '12345' RETURN b.name AS costar"));

        HopSpec hop = plan.getFirstHop();
        // a.name is the identity property — goes to identityEquals
        assertThat(hop.getSource().getIdentityEquals()).containsEntry("name", "jerry");
        // b.id is non-identity — goes to shardPropertyFilters
        assertThat(hop.getSink().getShardPropertyFilters()).containsEntry("id", "12345");
        assertThat(hop.getSink().requiresShardEnrichment()).isTrue();
    }

    // ---- M3: variable-length, aggregation, path binding ---------------------

    @Test
    public void plansVariableLengthRelWithBounds() {
        CypherPlan plan = planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF*1..3]-(b:Actor) RETURN b.name AS x"));
        HopSpec hop = plan.getFirstHop();
        assertThat(hop.isVariableLength()).isTrue();
        assertThat(hop.getLower()).hasValue(1);
        assertThat(hop.getUpper()).hasValue(3);
    }

    @Test
    public void plansPathVariablePattern() {
        CypherPlan plan = planner.plan(frontEnd.analyze(
                        "MATCH p = (a:Actor {name:'jerry'})-[:COSTAR_OF]-(b:Actor) RETURN p AS path"));
        assertThat(plan.getProjections()).hasSize(1);
        Projection p = plan.getProjections().get(0);
        assertThat(p.getKind()).isEqualTo(Projection.Kind.PATH_OBJECT);
        assertThat(p.getAlias()).isEqualTo("path");
        assertThat(p.getVariable()).isEqualTo("p");
        assertThat(plan.getFirstHop().getPathVariable()).hasValue("p");
    }

    @Test
    public void plansCountStarAggregate() {
        CypherPlan plan = planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]-(b:Actor) RETURN count(*) AS n"));
        assertThat(plan.getGroupingSpec()).isPresent();
        assertThat(plan.getGroupingSpec().get().getGroupByKeys()).isEmpty();
        assertThat(plan.getGroupingSpec().get().getAggregates()).hasSize(1);
        AggregateSpec spec = plan.getGroupingSpec().get().getAggregates().get(0).getAggregateSpec().orElseThrow();
        assertThat(spec.getFunc()).isEqualTo(AggregateSpec.Func.COUNT_STAR);
        assertThat(spec.isDistinct()).isFalse();
    }

    @Test
    public void plansAggregateWithImplicitGroupBy() {
        CypherPlan plan = planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor)-[:COSTAR_OF]-(b:Actor) WHERE a.name = 'jerry' RETURN a.name AS actor, count(b) AS n"));
        assertThat(plan.getGroupingSpec()).isPresent();
        assertThat(plan.getGroupingSpec().get().getGroupByKeys()).hasSize(1);
        assertThat(plan.getGroupingSpec().get().getGroupByKeys().get(0).getAlias()).isEqualTo("actor");
        assertThat(plan.getGroupingSpec().get().getAggregates()).hasSize(1);
        assertThat(plan.getGroupingSpec().get().getAggregates().get(0).getAlias()).isEqualTo("n");
    }

    @Test
    public void plansCountDistinctAggregate() {
        CypherPlan plan = planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]-(b:Actor) RETURN count(DISTINCT b.name) AS n"));
        AggregateSpec spec = plan.getGroupingSpec().orElseThrow().getAggregates().get(0).getAggregateSpec().orElseThrow();
        assertThat(spec.getFunc()).isEqualTo(AggregateSpec.Func.COUNT);
        assertThat(spec.isDistinct()).isTrue();
    }

    @Test
    public void rejectsUnboundedVarLengthAboveCap() {
        planner.setMaxVariableLengthUpper(3);
        assertThatThrownBy(() -> planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF*1..10]-(b:Actor) RETURN b.name AS x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("exceeds the configured cap");
    }

    @Test
    public void rejectsZeroLowerVarLength() {
        assertThatThrownBy(() -> planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF*0..3]-(b:Actor) RETURN b.name AS x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("zero-step");
    }

    @Test
    public void rejectsVarLengthRelVarInWhere() {
        assertThatThrownBy(() -> planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor {name:'jerry'})-[r:COSTAR_OF*1..2]-(b:Actor) WHERE r.show = 'Curb' RETURN b.name AS x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("variable-length");
    }

    // ---- Retained M1 structural checks --------------------------------------

    @Test
    public void rejectsOptionalMatch() {
        assertThatThrownBy(() -> planner.plan(frontEnd.analyze(
                        "OPTIONAL MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]-(b:Actor) RETURN b.name AS x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("OPTIONAL MATCH");
    }

    @Test
    public void rejectsUnknownLabel() {
        assertThatThrownBy(() -> planner.plan(frontEnd.analyze(
                        "MATCH (a:Director {name:'jerry'})-[:COSTAR_OF]-(b:Actor) RETURN b.name AS x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("Director");
    }

    @Test
    public void rejectsUnknownRelationshipType() {
        assertThatThrownBy(() -> planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor {name:'jerry'})-[:DIRECTED]-(b:Actor) RETURN b.name AS x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("DIRECTED");
    }

    @Test
    public void rejectsOrInWhere() {
        assertThatThrownBy(() -> planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor)-[:COSTAR_OF]-(b:Actor) WHERE a.name = 'jerry' OR a.name = 'kramer' RETURN b.name AS x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("OR");
    }

    @Test
    public void rejectsAnonymousNodeWithoutVariable() {
        assertThatThrownBy(() -> planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]-(:Actor) RETURN a.name AS x")))
                                        .isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("anonymous");
    }

    @Test
    public void smokeTestOutgoingDirection() {
        CypherPlan plan = planner.plan(frontEnd.analyze(
                        "MATCH (a:Actor {name:'jerry'})-[:COSTAR_OF]->(b:Actor) RETURN b.name AS x"));
        assertThat(plan.getFirstHop().getPatternDirection()).isEqualTo(RelationshipPattern.Direction.OUTGOING);
    }
}
