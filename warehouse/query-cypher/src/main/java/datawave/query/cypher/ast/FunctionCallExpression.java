package datawave.query.cypher.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FunctionCallExpression extends Expression {

    private final String name;
    private final boolean distinct;
    private final List<Expression> arguments;

    public FunctionCallExpression(SourceLocation location, String name, boolean distinct, List<Expression> arguments) {
        super(location);
        this.name = name;
        this.distinct = distinct;
        this.arguments = Collections.unmodifiableList(new ArrayList<>(arguments));
    }

    public String getName() {
        return name;
    }

    public boolean isDistinct() {
        return distinct;
    }

    public List<Expression> getArguments() {
        return arguments;
    }

    /**
     * Whether this function is one of the aggregating functions the planner
     * recognizes. Keeping the list here lets the semantic analyzer reject
     * aggregation in positions where it's not allowed (e.g. inside a WHERE)
     * without pulling in the full planner.
     */
    public boolean isAggregate() {
        if (name == null) {
            return false;
        }
        String upper = name.toUpperCase();
        return upper.equals("COUNT") || upper.equals("SUM") || upper.equals("AVG") || upper.equals("MIN") || upper.equals("MAX")
                        || upper.equals("COLLECT");
    }
}
