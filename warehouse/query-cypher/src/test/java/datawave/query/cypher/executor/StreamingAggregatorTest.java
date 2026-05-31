package datawave.query.cypher.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import datawave.query.cypher.planner.AggregateSpec;
import datawave.query.cypher.planner.CypherUnsupportedException;
import datawave.query.cypher.planner.GroupingSpec;
import datawave.query.cypher.planner.Projection;

public class StreamingAggregatorTest {

    @Test
    public void countStarReturnsTotalRows() {
        Projection countAll = Projection.aggregate("n", new AggregateSpec(AggregateSpec.Func.COUNT_STAR, null, null, false));
        GroupingSpec spec = new GroupingSpec(new ArrayList<>(), Arrays.asList(countAll));
        StreamingAggregator agg = new StreamingAggregator(spec, 1000);

        List<PathTuple> in = Arrays.asList(tuple("b", "x"), tuple("b", "y"), tuple("b", "x"));
        List<PathTuple> out = agg.aggregate(in);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).get(Projection.AGGREGATE_TUPLE_KEY_PREFIX + "n")).isEqualTo("3");
    }

    @Test
    public void countGroupsByNonAggregateProjection() {
        Projection keyA = new Projection("actor", "a", "name", Projection.Kind.NODE_PROPERTY);
        Projection countB = Projection.aggregate("n", new AggregateSpec(AggregateSpec.Func.COUNT, "b", "name", false));
        GroupingSpec spec = new GroupingSpec(Arrays.asList(keyA), Arrays.asList(countB));
        StreamingAggregator agg = new StreamingAggregator(spec, 1000);

        List<PathTuple> in = Arrays.asList(tupleMulti("a", "alice", "b", "x"), tupleMulti("a", "alice", "b", "y"), tupleMulti("a", "bob", "b", "z"));
        List<PathTuple> out = agg.aggregate(in);

        assertThat(out).hasSize(2);
        // Find the alice group
        PathTuple alice = out.stream().filter(t -> "alice".equals(t.get("a"))).findFirst().orElseThrow();
        PathTuple bob = out.stream().filter(t -> "bob".equals(t.get("a"))).findFirst().orElseThrow();
        assertThat(alice.get(Projection.AGGREGATE_TUPLE_KEY_PREFIX + "n")).isEqualTo("2");
        assertThat(bob.get(Projection.AGGREGATE_TUPLE_KEY_PREFIX + "n")).isEqualTo("1");
    }

    @Test
    public void countDistinctDedupesArgumentValues() {
        Projection countDistinct = Projection.aggregate("n", new AggregateSpec(AggregateSpec.Func.COUNT, "b", "name", true));
        GroupingSpec spec = new GroupingSpec(new ArrayList<>(), Arrays.asList(countDistinct));
        StreamingAggregator agg = new StreamingAggregator(spec, 1000);

        List<PathTuple> in = Arrays.asList(tuple("b", "x"), tuple("b", "y"), tuple("b", "x"), tuple("b", "y"), tuple("b", "z"));
        List<PathTuple> out = agg.aggregate(in);

        assertThat(out.get(0).get(Projection.AGGREGATE_TUPLE_KEY_PREFIX + "n")).isEqualTo("3");
    }

    @Test
    public void sumAndAvgComputeOverNumericProperty() {
        Projection sumScore = Projection.aggregate("s", new AggregateSpec(AggregateSpec.Func.SUM, "b", "score", false));
        Projection avgScore = Projection.aggregate("av", new AggregateSpec(AggregateSpec.Func.AVG, "b", "score", false));
        GroupingSpec spec = new GroupingSpec(new ArrayList<>(), Arrays.asList(sumScore, avgScore));
        StreamingAggregator agg = new StreamingAggregator(spec, 1000);

        List<PathTuple> in = Arrays.asList(tuple("b.score", "10"), tuple("b.score", "20"), tuple("b.score", "30"));
        List<PathTuple> out = agg.aggregate(in);

        assertThat(out.get(0).get(Projection.AGGREGATE_TUPLE_KEY_PREFIX + "s")).isEqualTo("60");
        assertThat(out.get(0).get(Projection.AGGREGATE_TUPLE_KEY_PREFIX + "av")).isEqualTo("20");
    }

    @Test
    public void minMaxFindExtremes() {
        Projection minName = Projection.aggregate("lo", new AggregateSpec(AggregateSpec.Func.MIN, "b", "name", false));
        Projection maxName = Projection.aggregate("hi", new AggregateSpec(AggregateSpec.Func.MAX, "b", "name", false));
        GroupingSpec spec = new GroupingSpec(new ArrayList<>(), Arrays.asList(minName, maxName));
        StreamingAggregator agg = new StreamingAggregator(spec, 1000);

        List<PathTuple> in = Arrays.asList(tuple("b", "charlie"), tuple("b", "alice"), tuple("b", "bob"));
        List<PathTuple> out = agg.aggregate(in);

        assertThat(out.get(0).get(Projection.AGGREGATE_TUPLE_KEY_PREFIX + "lo")).isEqualTo("alice");
        assertThat(out.get(0).get(Projection.AGGREGATE_TUPLE_KEY_PREFIX + "hi")).isEqualTo("charlie");
    }

    @Test
    public void groupCapExceededThrows() {
        Projection keyA = new Projection("actor", "a", "name", Projection.Kind.NODE_PROPERTY);
        Projection countB = Projection.aggregate("n", new AggregateSpec(AggregateSpec.Func.COUNT, "b", "name", false));
        GroupingSpec spec = new GroupingSpec(Arrays.asList(keyA), Arrays.asList(countB));
        StreamingAggregator agg = new StreamingAggregator(spec, 2);

        List<PathTuple> in = Arrays.asList(tupleMulti("a", "x", "b", "p"), tupleMulti("a", "y", "b", "q"), tupleMulti("a", "z", "b", "r"));
        assertThatThrownBy(() -> agg.aggregate(in)).isInstanceOf(CypherUnsupportedException.class).hasMessageContaining("group count exceeds cap");
    }

    private static PathTuple tuple(String key, String value) {
        Map<String,String> m = new LinkedHashMap<>();
        m.put(key, value);
        return PathTuple.of(m);
    }

    private static PathTuple tupleMulti(String... kvs) {
        Map<String,String> m = new LinkedHashMap<>();
        for (int i = 0; i < kvs.length; i += 2) {
            m.put(kvs[i], kvs[i + 1]);
        }
        return PathTuple.of(m);
    }
}
