package datawave.query.cypher.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A dotted property access: {@code variable.prop} or a chain
 * {@code variable.prop.nested}. Most Cypher property access is a single hop;
 * the grammar allows chaining so we preserve the full path.
 */
public final class PropertyExpression extends Expression {

    private final Expression target;
    private final List<String> propertyPath;

    public PropertyExpression(SourceLocation location, Expression target, List<String> propertyPath) {
        super(location);
        this.target = target;
        this.propertyPath = Collections.unmodifiableList(new ArrayList<>(propertyPath));
    }

    public Expression getTarget() {
        return target;
    }

    public List<String> getPropertyPath() {
        return propertyPath;
    }
}
