package datawave.query.cypher.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import datawave.query.cypher.CypherFrontEnd;

class AstPrinterTest {

    private final CypherFrontEnd frontEnd = new CypherFrontEnd();

    @Test
    void dumpsSingleHopAst() {
        String dump = frontEnd.getPlannedScript("MATCH (a:Actor {name: 'X'})-[:COSTAR_OF]-(b:Actor) RETURN b.name");
        assertThat(dump).contains("Query").contains("SingleQuery").contains("Match").contains("Pattern");
        assertThat(dump).contains("Node var=a labels=[Actor]");
        assertThat(dump).contains("Relationship dir=UNDIRECTED types=[COSTAR_OF]");
        assertThat(dump).contains("Node var=b labels=[Actor]");
        assertThat(dump).contains("Return").contains("Property path=[name]").contains("Var b");
    }

    @Test
    void dumpsVariableLengthRange() {
        String dump = frontEnd.getPlannedScript("MATCH (a)-[:COSTAR_OF*1..3]-(b) RETURN a, b");
        assertThat(dump).contains("length=[1..3]");
    }

    @Test
    void dumpsWithAndAggregation() {
        String dump = frontEnd.getPlannedScript(
                        "MATCH (a:Actor)-[:COSTAR_OF]-(b) WITH a, count(b) AS n WHERE n > 10 RETURN a.name AS name, n ORDER BY n DESC LIMIT 5");
        assertThat(dump).contains("With");
        assertThat(dump).contains("Call count [aggregate]");
        assertThat(dump).contains("SortItem DESC");
        assertThat(dump).contains("Limit");
    }
}
