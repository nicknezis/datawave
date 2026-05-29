package datawave.query.cypher.planner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import datawave.query.cypher.mapping.NodeMapping;

/**
 * One node endpoint of a planned hop. Records the Cypher variable name, the {@link NodeMapping schema mapping} resolved from its label, equality filters on its
 * identity property (used to build Accumulo scan ranges), and equality filters on non-identity properties (applied post-enrichment by
 * {@link datawave.query.cypher.executor.ShardEnrichmentService}).
 */
public final class NodeBinding {

    private final String variable;
    private final NodeMapping mapping;
    /** Equality filters on the identity property; drives edge-table scan ranges. */
    private final Map<String,String> identityEquals;
    /** Equality filters on non-identity properties; applied after shard enrichment. */
    private final Map<String,String> shardPropertyFilters;

    public NodeBinding(String variable, NodeMapping mapping, Map<String,String> identityEquals) {
        this(variable, mapping, identityEquals, Collections.emptyMap());
    }

    public NodeBinding(String variable, NodeMapping mapping, Map<String,String> identityEquals, Map<String,String> shardPropertyFilters) {
        this.variable = Objects.requireNonNull(variable, "variable");
        this.mapping = Objects.requireNonNull(mapping, "mapping");
        this.identityEquals = Collections.unmodifiableMap(new LinkedHashMap<>(identityEquals));
        this.shardPropertyFilters = Collections
                        .unmodifiableMap(shardPropertyFilters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(shardPropertyFilters));
    }

    public String getVariable() {
        return variable;
    }

    public NodeMapping getMapping() {
        return mapping;
    }

    /**
     * Equality filters on the identity property: an empty map means "match any vertex of this label"; a single entry means "match a vertex whose identity
     * equals this value".
     */
    public Map<String,String> getIdentityEquals() {
        return identityEquals;
    }

    public boolean hasIdentityFilter() {
        return !identityEquals.isEmpty();
    }

    /**
     * Equality filters on non-identity node properties. Applied by the executor after shard enrichment; empty map means no additional filter.
     */
    public Map<String,String> getShardPropertyFilters() {
        return shardPropertyFilters;
    }

    public boolean requiresShardEnrichment() {
        return !shardPropertyFilters.isEmpty();
    }
}
