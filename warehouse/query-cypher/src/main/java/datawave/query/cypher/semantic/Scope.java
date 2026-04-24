package datawave.query.cypher.semantic;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Ordered map of variable name → {@link Binding}. Scopes are chained by the
 * analyzer (MATCH adds to the current scope, WITH starts a fresh scope seeded
 * only with the projections). Variables may be reused across subsequent
 * MATCH clauses in the same scope only if their kind and declared labels
 * remain compatible; the analyzer enforces that.
 */
public final class Scope {

    private final Map<String,Binding> bindings = new LinkedHashMap<>();

    public Optional<Binding> lookup(String name) {
        return Optional.ofNullable(bindings.get(name));
    }

    public boolean contains(String name) {
        return bindings.containsKey(name);
    }

    public void put(Binding binding) {
        bindings.put(binding.getName(), binding);
    }

    public Collection<Binding> bindings() {
        return Collections.unmodifiableCollection(bindings.values());
    }

    public int size() {
        return bindings.size();
    }

    public Scope copy() {
        Scope other = new Scope();
        other.bindings.putAll(this.bindings);
        return other;
    }
}
