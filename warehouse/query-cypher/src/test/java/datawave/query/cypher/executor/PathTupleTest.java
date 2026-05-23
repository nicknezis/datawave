package datawave.query.cypher.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.accumulo.core.security.ColumnVisibility;
import org.junit.Test;

public class PathTupleTest {

    @Test
    public void mergeUnionsValuesAndPathsAndVisibilities() {
        PathTuple a = PathTuple.of(map("a", "alice")).extendPath("p", new PathElement.NodeElement("a", "alice", null))
                        .withVisibility(new ColumnVisibility("PUBLIC"));
        PathTuple b = PathTuple.of(map("b", "bob")).extendPath("p", new PathElement.EdgeElement(null, "KNOWS", "alice", "bob", null))
                        .extendPath("p", new PathElement.NodeElement("b", "bob", null)).withVisibility(new ColumnVisibility("PUBLIC"));

        PathTuple merged = a.merge(b);

        assertThat(merged.get("a")).isEqualTo("alice");
        assertThat(merged.get("b")).isEqualTo("bob");
        assertThat(merged.getPath("p")).hasSize(3);
        assertThat(((PathElement.NodeElement) merged.getPath("p").get(0)).getIdentity()).isEqualTo("alice");
        assertThat(((PathElement.EdgeElement) merged.getPath("p").get(1)).getType()).isEqualTo("KNOWS");
        assertThat(((PathElement.NodeElement) merged.getPath("p").get(2)).getIdentity()).isEqualTo("bob");
        assertThat(merged.getVisibilities()).hasSize(2);
    }

    @Test
    public void v2BytesRoundTripPathsAndVisibilities() {
        PathTuple original = PathTuple.of(map("a", "alice", "b.score", "42")).extendPath("p", new PathElement.NodeElement("a", "alice", null))
                        .extendPath("p", new PathElement.EdgeElement("r", "KNOWS", "alice", "bob", map("since", "2020")))
                        .extendPath("p", new PathElement.NodeElement("b", "bob", null)).recordEdgeId("p", 42L).withVisibility(new ColumnVisibility("PUBLIC"));

        byte[] bytes = original.toBytes();
        PathTuple restored = PathTuple.fromBytes(bytes);

        assertThat(restored.get("a")).isEqualTo("alice");
        assertThat(restored.get("b.score")).isEqualTo("42");
        List<PathElement> path = restored.getPath("p");
        assertThat(path).hasSize(3);
        assertThat(((PathElement.NodeElement) path.get(0)).getIdentity()).isEqualTo("alice");
        PathElement.EdgeElement edge = (PathElement.EdgeElement) path.get(1);
        assertThat(edge.getType()).isEqualTo("KNOWS");
        assertThat(edge.getAttributes()).containsEntry("since", "2020");
        assertThat(restored.containsEdgeId("p", 42L)).isTrue();
        assertThat(restored.getVisibilities()).hasSize(1);
        assertThat(new String(restored.getVisibilities().get(0).getExpression(), StandardCharsets.UTF_8)).isEqualTo("PUBLIC");
    }

    @Test
    public void v1BytesDeserialiseUnderV2Reader() {
        // v1 format: KV_SEP / PAIR_SEP delimited UTF-8.
        String v1 = "a" + PathTuple.KV_SEP + "alice" + PathTuple.PAIR_SEP + "b" + PathTuple.KV_SEP + "bob";
        PathTuple restored = PathTuple.fromBytes(v1.getBytes(StandardCharsets.UTF_8));
        assertThat(restored.get("a")).isEqualTo("alice");
        assertThat(restored.get("b")).isEqualTo("bob");
        assertThat(restored.getPath("p")).isEmpty();
        assertThat(restored.getVisibilities()).isEmpty();
    }

    @Test
    public void recordEdgeIdTracksTrailHistoryPerPath() {
        PathTuple t = PathTuple.empty().recordEdgeId("p", 1L).recordEdgeId("p", 2L).recordEdgeId("q", 1L);
        assertThat(t.containsEdgeId("p", 1L)).isTrue();
        assertThat(t.containsEdgeId("p", 2L)).isTrue();
        assertThat(t.containsEdgeId("p", 3L)).isFalse();
        assertThat(t.containsEdgeId("q", 1L)).isTrue();
        assertThat(t.containsEdgeId("q", 2L)).isFalse();
        assertThat(t.pathEdgeHistorySize("p")).isEqualTo(2);
    }

    private static Map<String,String> map(String... kvs) {
        Map<String,String> m = new LinkedHashMap<>();
        for (int i = 0; i < kvs.length; i += 2) {
            m.put(kvs[i], kvs[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("unused")
    private static List<String> list(String... values) {
        return Arrays.asList(values);
    }
}
