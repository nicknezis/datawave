package datawave.query.cypher.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

import datawave.query.cypher.ast.CypherQuery;
import datawave.query.cypher.parser.CypherParser;
import datawave.query.cypher.parser.CypherSyntaxException;

class SemanticAnalyzerTest {

    private final CypherParser parser = new CypherParser();

    private BindingTable analyze(String q) {
        CypherQuery ast = parser.parse(q);
        return new SemanticAnalyzer().analyze(ast);
    }

    @Test
    void recordsSimpleBindings() {
        BindingTable table = analyze("MATCH (a:Actor)-[r:COSTAR_OF]-(b:Actor) RETURN a, b");
        Scope scope = table.getScopes().get(0);
        assertThat(scope.lookup("a").orElseThrow().getType()).isEqualTo(BoundType.NODE);
        assertThat(scope.lookup("a").orElseThrow().getLabelsOrTypes()).containsExactly("Actor");
        assertThat(scope.lookup("r").orElseThrow().getType()).isEqualTo(BoundType.RELATIONSHIP);
        assertThat(scope.lookup("r").orElseThrow().getLabelsOrTypes()).containsExactly("COSTAR_OF");
        assertThat(scope.lookup("b").orElseThrow().getType()).isEqualTo(BoundType.NODE);
    }

    @Test
    void reusesNodeVariableAcrossMatchesUnioningLabels() {
        BindingTable table = analyze("MATCH (a:Actor) MATCH (a:Person) RETURN a");
        assertThat(table.getScopes().get(0).lookup("a").orElseThrow().getLabelsOrTypes()).containsExactly("Actor", "Person");
    }

    @Test
    void rejectsTypeMismatchOnReusedVariable() {
        assertThatExceptionOfType(SemanticException.class).isThrownBy(() -> analyze("MATCH (a:Actor)-[a:KNOWS]-(b) RETURN a"));
    }

    @Test
    void rejectsUndefinedVariableInWhere() {
        assertThatExceptionOfType(SemanticException.class).isThrownBy(() -> analyze("MATCH (a:Actor) WHERE b.name = 'X' RETURN a"));
    }

    @Test
    void rejectsUndefinedVariableInReturn() {
        assertThatExceptionOfType(SemanticException.class).isThrownBy(() -> analyze("MATCH (a:Actor) RETURN b.name"));
    }

    @Test
    void rejectsUnboundedVariableLengthAtParseTime() {
        // Unbounded [*] and half-bounded [*n] / [*..n] / [*n..] forms are now rejected by the grammar,
        // so they surface as syntax errors rather than semantic ones.
        assertThatExceptionOfType(CypherSyntaxException.class).isThrownBy(() -> analyze("MATCH (a)-[r:KNOWS*]->(b) RETURN a, b"));
        assertThatExceptionOfType(CypherSyntaxException.class).isThrownBy(() -> analyze("MATCH (a)-[r:KNOWS*3]->(b) RETURN a, b"));
    }

    @Test
    void rejectsInvertedVariableLengthBounds() {
        assertThatExceptionOfType(SemanticException.class).isThrownBy(() -> analyze("MATCH (a)-[r:KNOWS*5..2]->(b) RETURN a, b"));
    }

    @Test
    void rejectsAggregateInWhere() {
        assertThatExceptionOfType(SemanticException.class)
                        .isThrownBy(() -> analyze("MATCH (a:Actor)-[:COSTAR_OF]-(b) WHERE count(b) > 10 RETURN a"));
    }

    @Test
    void allowsAggregateInProjection() {
        BindingTable table = analyze("MATCH (a:Actor)-[:COSTAR_OF]-(b) RETURN a.name AS name, count(b) AS costars");
        Scope ret = table.getReturnScope().orElseThrow();
        assertThat(ret.contains("name")).isTrue();
        assertThat(ret.contains("costars")).isTrue();
    }

    @Test
    void rejectsProjectionWithoutImplicitName() {
        assertThatExceptionOfType(SemanticException.class).isThrownBy(() -> analyze("MATCH (a:Actor) RETURN a.name + 1"));
    }

    @Test
    void rejectsDuplicateProjectionName() {
        assertThatExceptionOfType(SemanticException.class).isThrownBy(() -> analyze("MATCH (a:Actor) RETURN a.name AS name, a.born AS name"));
    }

    @Test
    void withIntroducesFreshScope() {
        BindingTable table = analyze("MATCH (a:Actor)-[:COSTAR_OF]-(b:Actor) WITH b AS x MATCH (x)-[:COSTAR_OF]-(c:Actor) RETURN c.name");
        assertThat(table.getScopes()).hasSize(2);
        Scope afterWith = table.getScopes().get(1);
        assertThat(afterWith.contains("x")).isTrue();
        assertThat(afterWith.contains("c")).isTrue();
        assertThat(afterWith.contains("a")).isFalse();
    }

    @Test
    void withStarCarriesEverythingForward() {
        BindingTable table = analyze("MATCH (a:Actor)-[:COSTAR_OF]-(b:Actor) WITH * MATCH (a)-[:COSTAR_OF]-(c:Actor) RETURN c.name");
        Scope afterWith = table.getScopes().get(1);
        assertThat(afterWith.contains("a")).isTrue();
        assertThat(afterWith.contains("b")).isTrue();
        assertThat(afterWith.contains("c")).isTrue();
    }

    @Test
    void rejectsUnionAsOutOfScope() {
        assertThatExceptionOfType(SemanticException.class)
                        .isThrownBy(() -> analyze("MATCH (a:Actor) RETURN a.name UNION MATCH (b:Director) RETURN b.name"));
    }

    @Test
    void rejectsLimitWithNonInteger() {
        assertThatExceptionOfType(SemanticException.class).isThrownBy(() -> analyze("MATCH (a:Actor) RETURN a LIMIT a.born"));
    }

    @Test
    void acceptsLimitParameter() {
        BindingTable table = analyze("MATCH (a:Actor) RETURN a LIMIT $maxRows");
        assertThat(table.getReturnScope()).isPresent();
    }
}
