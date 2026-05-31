package datawave.query.cypher.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;

import org.junit.jupiter.api.Test;

import datawave.query.cypher.ast.BinaryExpression;
import datawave.query.cypher.ast.CypherQuery;
import datawave.query.cypher.ast.FunctionCallExpression;
import datawave.query.cypher.ast.LiteralExpression;
import datawave.query.cypher.ast.MatchClause;
import datawave.query.cypher.ast.NodePattern;
import datawave.query.cypher.ast.Pattern;
import datawave.query.cypher.ast.ProjectionItem;
import datawave.query.cypher.ast.PropertiesExpression;
import datawave.query.cypher.ast.PropertyExpression;
import datawave.query.cypher.ast.ReadingClause;
import datawave.query.cypher.ast.RelationshipPattern;
import datawave.query.cypher.ast.ReturnClause;
import datawave.query.cypher.ast.SingleQuery;
import datawave.query.cypher.ast.VariableExpression;

class CypherParserTest {

    private final CypherParser parser = new CypherParser();

    @Test
    void parsesSimpleSingleHopMatch() {
        CypherQuery q = parser.parse("MATCH (a:Actor {name: 'X'})-[:COSTAR_OF]-(b:Actor) RETURN b.name");
        SingleQuery single = q.getBranches().get(0);

        List<ReadingClause> reading = single.getReadingClauses();
        assertThat(reading).hasSize(1);
        MatchClause match = (MatchClause) reading.get(0);
        assertThat(match.isOptional()).isFalse();
        assertThat(match.getPatterns()).hasSize(1);

        Pattern pattern = match.getPatterns().get(0);
        assertThat(pattern.getPathVariable()).isEmpty();

        List<NodePattern> nodes = pattern.getElement().getNodes();
        List<RelationshipPattern> rels = pattern.getElement().getRelationships();
        assertThat(nodes).hasSize(2);
        assertThat(rels).hasSize(1);

        assertThat(nodes.get(0).getVariable()).hasValue("a");
        assertThat(nodes.get(0).getLabels()).containsExactly("Actor");
        PropertiesExpression props = nodes.get(0).getProperties().orElseThrow();
        LiteralExpression map = (LiteralExpression) props.getExpression();
        assertThat(map.getKind()).isEqualTo(LiteralExpression.Kind.MAP);
        LiteralExpression name = (LiteralExpression) map.asMap().get("name");
        assertThat(name.getKind()).isEqualTo(LiteralExpression.Kind.STRING);
        assertThat(name.getValue()).isEqualTo("X");

        assertThat(rels.get(0).getDirection()).isEqualTo(RelationshipPattern.Direction.UNDIRECTED);
        assertThat(rels.get(0).getTypes()).containsExactly("COSTAR_OF");

        ReturnClause ret = single.getReturnClause().orElseThrow();
        assertThat(ret.getProjections()).hasSize(1);
        PropertyExpression prop = (PropertyExpression) ret.getProjections().get(0).getExpression();
        assertThat(((VariableExpression) prop.getTarget()).getName()).isEqualTo("b");
        assertThat(prop.getPropertyPath()).containsExactly("name");
    }

    @Test
    void parsesDirectedRelationship() {
        CypherQuery q = parser.parse("MATCH (a)-[:KNOWS]->(b) RETURN a, b");
        RelationshipPattern rel = firstMatch(q).getPatterns().get(0).getElement().getRelationships().get(0);
        assertThat(rel.getDirection()).isEqualTo(RelationshipPattern.Direction.OUTGOING);
    }

    @Test
    void parsesIncomingRelationship() {
        CypherQuery q = parser.parse("MATCH (a)<-[:KNOWS]-(b) RETURN a, b");
        RelationshipPattern rel = firstMatch(q).getPatterns().get(0).getElement().getRelationships().get(0);
        assertThat(rel.getDirection()).isEqualTo(RelationshipPattern.Direction.INCOMING);
    }

    @Test
    void parsesVariableLengthRelationship() {
        CypherQuery q = parser.parse("MATCH (a)-[:COSTAR_OF*1..3]-(b) RETURN a, b");
        RelationshipPattern rel = firstMatch(q).getPatterns().get(0).getElement().getRelationships().get(0);
        assertThat(rel.isVariableLength()).isTrue();
        assertThat(rel.getLower().orElseThrow()).isEqualTo(1);
        assertThat(rel.getUpper().orElseThrow()).isEqualTo(3);
    }

    @Test
    void parsesWhereAndReturnWithOrderAndLimit() {
        CypherQuery q = parser.parse("MATCH (a:Actor) WHERE a.born > 1960 AND a.name <> 'Y' RETURN a.name AS name ORDER BY a.born DESC LIMIT 5");
        MatchClause match = firstMatch(q);
        BinaryExpression where = (BinaryExpression) match.getWhere().orElseThrow();
        assertThat(where.getOperator()).isEqualTo(BinaryExpression.Operator.AND);

        ReturnClause ret = q.getBranches().get(0).getReturnClause().orElseThrow();
        ProjectionItem item = ret.getProjections().get(0);
        assertThat(item.getAlias()).hasValue("name");
        assertThat(ret.getOrderBy()).hasSize(1);
        assertThat(ret.getOrderBy().get(0).getDirection().name()).isEqualTo("DESC");
        LiteralExpression limit = (LiteralExpression) ret.getLimit().orElseThrow();
        assertThat(limit.getKind()).isEqualTo(LiteralExpression.Kind.INTEGER);
        assertThat((long) limit.getValue()).isEqualTo(5L);
    }

    @Test
    void parsesAggregationInReturn() {
        CypherQuery q = parser.parse("MATCH (a:Actor)-[:COSTAR_OF]-(b) RETURN a.name, count(b) AS costars");
        ReturnClause ret = q.getBranches().get(0).getReturnClause().orElseThrow();
        assertThat(ret.getProjections()).hasSize(2);
        FunctionCallExpression fn = (FunctionCallExpression) ret.getProjections().get(1).getExpression();
        assertThat(fn.getName()).isEqualToIgnoringCase("count");
        assertThat(fn.isAggregate()).isTrue();
    }

    @Test
    void rejectsUnknownClauseAtParseTime() {
        assertThatExceptionOfType(CypherSyntaxException.class).isThrownBy(() -> parser.parse("CREATE (a:Actor {name: 'X'}) RETURN a"));
    }

    @Test
    void rejectsProcedureCallAtParseTime() {
        assertThatExceptionOfType(CypherSyntaxException.class).isThrownBy(() -> parser.parse("CALL db.labels() YIELD label RETURN label"));
    }

    @Test
    void rejectsUnbalancedPattern() {
        assertThatExceptionOfType(CypherSyntaxException.class).isThrownBy(() -> parser.parse("MATCH (a RETURN a"));
    }

    @Test
    void roundTripsStringEscapes() {
        CypherQuery q = parser.parse("MATCH (a {name: 'O\\'Malley'}) RETURN a");
        LiteralExpression map = (LiteralExpression) firstMatch(q).getPatterns().get(0).getElement().getNodes().get(0).getProperties().orElseThrow()
                        .getExpression();
        LiteralExpression name = (LiteralExpression) map.asMap().get("name");
        assertThat(name.getValue()).isEqualTo("O'Malley");
    }

    @Test
    void parsesMultipleMatchesWithWith() {
        CypherQuery q = parser.parse("MATCH (a:Actor {name:'X'})-[:COSTAR_OF]-(b:Actor) WITH b MATCH (b)-[:COSTAR_OF]-(c:Actor) RETURN c.name");
        assertThat(q.getBranches().get(0).getReadingClauses()).hasSize(3);
    }

    @Test
    void parsesParameterInProperties() {
        CypherQuery q = parser.parse("MATCH (a:Actor {name: $actorName}) RETURN a");
        assertThat(firstMatch(q).getPatterns().get(0).getElement().getNodes().get(0).getProperties()).isPresent();
    }

    private MatchClause firstMatch(CypherQuery q) {
        return (MatchClause) q.getBranches().get(0).getReadingClauses().get(0);
    }
}
