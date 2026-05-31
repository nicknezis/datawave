package datawave.query.cypher.planner;

import java.util.Objects;

import datawave.query.cypher.ast.SortItem;

/**
 * One ORDER BY item in the logical plan: the projected alias to sort on and the sort direction. ORDER BY is applied in-memory after the full hop chain
 * completes; only projected aliases (not arbitrary expressions) are accepted in M2.
 */
public final class SortSpec {

    private final String alias;
    private final SortItem.Direction direction;

    public SortSpec(String alias, SortItem.Direction direction) {
        this.alias = Objects.requireNonNull(alias, "alias");
        this.direction = Objects.requireNonNull(direction, "direction");
    }

    public String getAlias() {
        return alias;
    }

    public SortItem.Direction getDirection() {
        return direction;
    }
}
