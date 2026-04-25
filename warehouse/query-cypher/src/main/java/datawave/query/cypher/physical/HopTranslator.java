package datawave.query.cypher.physical;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.accumulo.core.data.Range;
import org.apache.hadoop.io.Text;

import datawave.query.cypher.mapping.EdgeAttributeSlot;
import datawave.query.cypher.mapping.EdgeDirection;
import datawave.query.cypher.planner.CypherPlanner;
import datawave.query.cypher.planner.CypherUnsupportedException;
import datawave.query.cypher.planner.HopSpec;
import datawave.query.cypher.planner.NodeBinding;

/**
 * Translates a single {@link HopSpec} into a set of Accumulo {@link Range}s
 * plus a JEXL filter string compatible with
 * {@link datawave.query.iterator.filter.EdgeFilterIterator}.
 *
 * <p>The architectural target (per the feasibility plan) is for the planner
 * to emit a JEXL AST and let
 * {@link datawave.query.jexl.visitors.EdgeTableRangeBuildingVisitor} build
 * the ranges. That visitor is general-purpose and supports nested OR/AND
 * trees with normalized data types, regex, etc. — none of which M1 needs.
 * For the single-AND identity-equality shape M1 produces, building ranges
 * directly is materially simpler and avoids dragging in EdgeModelFields
 * Spring wiring. M2 will swap this implementation for the visitor as the
 * supported query shapes grow past what direct construction can handle.
 *
 * <p>Row format on the edge table is {@code source\0sink}; ranges below
 * follow that convention with {@code \0} as the separator and {@code \1}
 * as the seek-stop sentinel.
 */
public final class HopTranslator {

    private static final char SEP = '\u0000';
    private static final char STOP = '\u0001';

    public HopTranslation translate(HopSpec hop) {
        boolean sourceHasIdentity = hop.getSource().hasIdentityFilter();
        boolean sinkHasIdentity = hop.getSink().hasIdentityFilter();

        if (!sourceHasIdentity && !sinkHasIdentity) {
            throw new CypherUnsupportedException("M1 requires at least one endpoint to filter on its identity property; "
                            + "unbounded relationship scans are deferred to M2");
        }

        boolean swap = false;
        NodeBinding scanSource = hop.getSource();
        NodeBinding scanSink = hop.getSink();
        if (!sourceHasIdentity && hop.getRel().getDirection() == EdgeDirection.UNDIRECTED) {
            // Bidirectional edges write both forward and reverse rows, so
            // pivoting onto the side with an identity filter gives an
            // efficient row-keyed scan for the same logical result set.
            scanSource = hop.getSink();
            scanSink = hop.getSource();
            swap = true;
        }

        Set<Range> ranges = buildRanges(scanSource, scanSink);
        String filterJexl = buildFilterJexl(hop, scanSource, scanSink);
        return new HopTranslation(ranges, filterJexl, swap);
    }

    private Set<Range> buildRanges(NodeBinding scanSource, NodeBinding scanSink) {
        Set<Range> out = new LinkedHashSet<>();
        Map<String,String> sourceEquals = scanSource.getIdentityEquals();
        Map<String,String> sinkEquals = scanSink.getIdentityEquals();

        if (sourceEquals.isEmpty() && sinkEquals.isEmpty()) {
            // Already prevented above; defensive.
            throw new IllegalStateException("buildRanges called without any identity filter");
        }

        if (!sourceEquals.isEmpty() && !sinkEquals.isEmpty()) {
            for (String src : sourceEquals.values()) {
                for (String snk : sinkEquals.values()) {
                    String row = src + SEP + snk;
                    out.add(new Range(new Text(row), true, new Text(row + SEP), false));
                }
            }
            return out;
        }
        if (!sourceEquals.isEmpty()) {
            for (String src : sourceEquals.values()) {
                String start = src + SEP;
                String end = src + STOP;
                out.add(new Range(new Text(start), true, new Text(end), false));
            }
            return out;
        }
        // sink-only: full table scan filtered by EdgeFilterIterator. Useful
        // diagnostic; not what we want for production. M1 only reaches here
        // for a directed schema-rel where the sink-only filter is the only
        // option. Document that clearly.
        out.add(new Range());
        return out;
    }

    private String buildFilterJexl(HopSpec hop, NodeBinding scanSource, NodeBinding scanSink) {
        // EdgeFilterIterator's JexlContext sets fields under the
        // EdgeModelFields.FieldKey enum names lowercased and lowercases
        // the JEXL itself before evaluation; we emit with the canonical
        // FieldKey names ("EDGE_SOURCE", "EDGE_TYPE", ...) and lowercase
        // the literal values to match the iterator's value normalization.
        List<String> terms = new ArrayList<>();
        terms.add("EDGE_TYPE == " + jexlString(hop.getRel().getEdgeType().toLowerCase()));
        for (String src : scanSource.getIdentityEquals().values()) {
            terms.add("EDGE_SOURCE == " + jexlString(src.toLowerCase()));
        }
        for (String snk : scanSink.getIdentityEquals().values()) {
            terms.add("EDGE_SINK == " + jexlString(snk.toLowerCase()));
        }
        for (Map.Entry<String,String> attr : hop.getAttributeEquals().entrySet()) {
            EdgeAttributeSlot slot = hop.getRel().resolveAttributeSlot(attr.getKey()).orElseThrow();
            terms.add(CypherPlanner.edgeAttributeFieldName(slot) + " == " + jexlString(attr.getValue().toLowerCase()));
        }
        return String.join(" && ", terms);
    }

    private static String jexlString(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
