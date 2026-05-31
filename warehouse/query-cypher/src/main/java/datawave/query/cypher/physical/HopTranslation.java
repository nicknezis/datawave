package datawave.query.cypher.physical;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import org.apache.accumulo.core.data.Range;

/**
 * The Accumulo-side scan plan for a single {@link datawave.query.cypher.planner.HopSpec hop}: the set of row {@link Range}s to seek over, the JEXL string the
 * {@link datawave.query.iterator.filter.EdgeFilterIterator} will evaluate against each candidate cell, and a flag indicating whether scan-time source/sink swap
 * was used (which the transformer reverses so emitted Cypher rows still bind the user's original source/sink variable names).
 */
public final class HopTranslation {

    private final Set<Range> ranges;
    private final String filterJexl;
    private final boolean swappedEndpoints;

    public HopTranslation(Set<Range> ranges, String filterJexl, boolean swappedEndpoints) {
        this.ranges = Collections.unmodifiableSet(new LinkedHashSet<>(ranges));
        this.filterJexl = Objects.requireNonNull(filterJexl, "filterJexl");
        this.swappedEndpoints = swappedEndpoints;
    }

    public Set<Range> getRanges() {
        return ranges;
    }

    public String getFilterJexl() {
        return filterJexl;
    }

    /**
     * True when the planner took advantage of an undirected (bidirectional) edge type to swap "filter on sink" into "filter on source", giving an efficient
     * row-keyed scan. The transformer must un-swap so emitted tuples bind the user's original Cypher source/sink variables.
     */
    public boolean isSwappedEndpoints() {
        return swappedEndpoints;
    }
}
