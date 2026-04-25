package datawave.query.cypher.planner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import datawave.query.cypher.mapping.NodeMapping;

/**
 * One node endpoint of a planned hop. Records the Cypher variable name, the
 * {@link NodeMapping schema mapping} resolved from its label, and any
 * equality filters on its identity property collected from the pattern's
 * inline properties or WHERE.
 * <p>
 * Carrying only identity-property filters reflects the M1 scope: filters
 * on non-identity node properties are rejected at plan time as not yet
 * supported (planned for M2 with shard-table enrichment).
 */
public final class NodeBinding {

    private final String variable;
    private final NodeMapping mapping;
    private final Map<String,String> identityEquals;

    public NodeBinding(String variable, NodeMapping mapping, Map<String,String> identityEquals) {
        this.variable = Objects.requireNonNull(variable, "variable");
        this.mapping = Objects.requireNonNull(mapping, "mapping");
        this.identityEquals = Collections.unmodifiableMap(new LinkedHashMap<>(identityEquals));
    }

    public String getVariable() {
        return variable;
    }

    public NodeMapping getMapping() {
        return mapping;
    }

    /**
     * Equality filters on the identity property: zero entries means
     * "match any vertex of this label"; one or more entries means
     * "match a vertex whose identity equals one of these values".
     */
    public Map<String,String> getIdentityEquals() {
        return identityEquals;
    }

    public boolean hasIdentityFilter() {
        return !identityEquals.isEmpty();
    }
}
