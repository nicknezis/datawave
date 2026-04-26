package datawave.query.cypher.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class GraphSchemaTest {

    @Test
    public void loadsTvmazeSchemaFromClasspath() {
        GraphSchema schema = GraphSchemaLoader.load("config/cypher-graph-schema.xml");
        assertThat(schema.getNodes()).containsKey("Actor");
        NodeMapping actor = schema.requireNode("Actor");
        assertThat(actor.getDataType()).isEqualTo("tvmaze");
        assertThat(actor.getIdentityProperty()).isEqualTo("name");
        assertThat(actor.getIdentityField()).isEqualTo("EMBEDDED_CAST_PERSON_NAME");
        assertThat(actor.resolveField("id")).contains("EMBEDDED_CAST_PERSON_ID");

        RelMapping costar = schema.requireRelationship("COSTAR_OF");
        assertThat(costar.getEdgeType()).isEqualTo("TV_COSTARS");
        assertThat(costar.getDirection()).isEqualTo(EdgeDirection.UNDIRECTED);
        assertThat(costar.getSourceLabel()).isEqualTo("Actor");
        assertThat(costar.getSinkLabel()).isEqualTo("Actor");
        assertThat(costar.resolveAttributeSlot("show")).contains(EdgeAttributeSlot.ATTRIBUTE2);
        assertThat(costar.resolveAttributeSlot("showId")).contains(EdgeAttributeSlot.ATTRIBUTE3);
    }

    @Test
    public void rejectsRelationshipReferencingUnknownLabel() {
        NodeMapping actor = new NodeMapping("Actor", "tvmaze", "name", "EMBEDDED_CAST_PERSON_NAME", new LinkedHashMap<>());
        RelMapping bogus = new RelMapping("X", "X", "Actor", "Show", EdgeDirection.UNDIRECTED, Collections.emptyMap());
        assertThatThrownBy(() -> new GraphSchema(0L, List.of(actor), List.of(bogus))).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Show");
    }

    @Test
    public void identityPropertyIsAlwaysPresentInPropertiesMap() {
        Map<String,String> noIdentity = new LinkedHashMap<>();
        noIdentity.put("nickname", "EMBEDDED_NICKNAME");
        NodeMapping actor = new NodeMapping("Actor", "tvmaze", "name", "EMBEDDED_CAST_PERSON_NAME", noIdentity);
        assertThat(actor.getProperties()).containsEntry("name", "EMBEDDED_CAST_PERSON_NAME").containsEntry("nickname", "EMBEDDED_NICKNAME");
    }
}
