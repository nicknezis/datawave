package datawave.query.cypher.ast;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class NodePattern extends AstNode {

    private final String variable;
    private final List<String> labels;
    private final PropertiesExpression properties;

    public NodePattern(SourceLocation location, String variable, List<String> labels, PropertiesExpression properties) {
        super(location);
        this.variable = variable;
        this.labels = Collections.unmodifiableList(labels);
        this.properties = properties;
    }

    public Optional<String> getVariable() {
        return Optional.ofNullable(variable);
    }

    public List<String> getLabels() {
        return labels;
    }

    public Optional<PropertiesExpression> getProperties() {
        return Optional.ofNullable(properties);
    }
}
