package datawave.query.cypher.planner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import datawave.query.cypher.CypherFrontEnd.Analysis;
import datawave.query.cypher.ast.BinaryExpression;
import datawave.query.cypher.ast.CypherQuery;
import datawave.query.cypher.ast.Expression;
import datawave.query.cypher.ast.FunctionCallExpression;
import datawave.query.cypher.ast.LabelCheckExpression;
import datawave.query.cypher.ast.LiteralExpression;
import datawave.query.cypher.ast.MatchClause;
import datawave.query.cypher.ast.NodePattern;
import datawave.query.cypher.ast.Pattern;
import datawave.query.cypher.ast.PatternElement;
import datawave.query.cypher.ast.ProjectionItem;
import datawave.query.cypher.ast.PropertiesExpression;
import datawave.query.cypher.ast.PropertyExpression;
import datawave.query.cypher.ast.ReadingClause;
import datawave.query.cypher.ast.RelationshipPattern;
import datawave.query.cypher.ast.ReturnClause;
import datawave.query.cypher.ast.SingleQuery;
import datawave.query.cypher.ast.UnaryExpression;
import datawave.query.cypher.ast.VariableExpression;
import datawave.query.cypher.ast.WithClause;
import datawave.query.cypher.mapping.EdgeAttributeSlot;
import datawave.query.cypher.mapping.GraphSchema;
import datawave.query.cypher.mapping.NodeMapping;
import datawave.query.cypher.mapping.RelMapping;

/**
 * Translates a parsed + semantically-analyzed Cypher query into a single-hop
 * {@link CypherPlan}. Rejects (with {@link CypherUnsupportedException})
 * anything outside the M1 surface so authors get a precise diagnostic at
 * plan time rather than partial execution.
 *
 * <p>M1 surface:
 * <ul>
 *   <li>One {@code MATCH (a:Label1)-[r:REL]-(b:Label2)} pattern (any
 *       direction). Both endpoint labels must resolve in the schema; the
 *       relationship type must resolve and its endpoint labels must match
 *       the pattern's labels.</li>
 *   <li>Inline node-property maps and WHERE clause restricted to equality
 *       on the resolved identity property of either endpoint, or equality
 *       on edge attributes via the relationship variable. Conjunctions
 *       (AND) supported; disjunction, negation, comparisons other than
 *       equality, regex, function calls, and parameters are not.</li>
 *   <li>RETURN of {@code variable.property} items only. The property must
 *       resolve to either the node identity property (which is read from
 *       the edge SOURCE/SINK) or an edge attribute slot (from the edge
 *       column qualifier). Non-identity node properties are rejected with
 *       a "planned for M2 (shard enrichment)" message.</li>
 *   <li>Optional LIMIT (integer literal). DISTINCT, ORDER BY, SKIP, and
 *       aggregation reject as M2/M3.</li>
 * </ul>
 */
public final class CypherPlanner {

    private final GraphSchema schema;

    public CypherPlanner(GraphSchema schema) {
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    public CypherPlan plan(Analysis analysis) {
        Objects.requireNonNull(analysis, "analysis");
        CypherQuery ast = analysis.getAst();
        if (ast.getBranches().size() != 1) {
            throw new CypherUnsupportedException("UNION is not supported in M1");
        }
        SingleQuery sq = ast.getBranches().get(0);

        MatchClause match = singleMatch(sq);
        ReturnClause ret = sq.getReturnClause()
                        .orElseThrow(() -> new CypherUnsupportedException("RETURN clause is required in M1; queries without RETURN are not supported"));

        if (match.isOptional()) {
            throw new CypherUnsupportedException("OPTIONAL MATCH is not supported in M1");
        }
        if (match.getPatterns().size() != 1) {
            throw new CypherUnsupportedException("M1 supports exactly one pattern per MATCH; multiple comma-separated patterns are deferred to M2");
        }

        Pattern pattern = match.getPatterns().get(0);
        if (pattern.getPathVariable().isPresent()) {
            throw new CypherUnsupportedException("path variables (p = ...) are deferred to M3");
        }

        PatternElement element = pattern.getElement();
        if (element.getRelationships().size() != 1) {
            throw new CypherUnsupportedException("M1 supports exactly one relationship per pattern; multi-hop is deferred to M2");
        }
        RelationshipPattern rel = element.getRelationships().get(0);
        if (rel.isVariableLength()) {
            throw new CypherUnsupportedException("variable-length relationships [*lo..hi] are deferred to M3");
        }
        if (rel.getTypes().size() != 1) {
            throw new CypherUnsupportedException("M1 requires exactly one relationship type, e.g. -[:KNOWS]-");
        }

        NodePattern leftAst = element.getNodes().get(0);
        NodePattern rightAst = element.getNodes().get(1);

        RelMapping relMapping = schema.getRelationship(rel.getTypes().get(0))
                        .orElseThrow(() -> new CypherUnsupportedException("relationship type not in graph schema: " + rel.getTypes().get(0)));

        NodeMapping leftMapping = resolveNodeMapping(leftAst, "left endpoint");
        NodeMapping rightMapping = resolveNodeMapping(rightAst, "right endpoint");

        // Cypher's relationship direction puts source on one side; reorient
        // so the bound source/sink match the schema's declared shape (so
        // SOURCE/SINK semantics on the edge row line up).
        boolean leftIsSource;
        switch (rel.getDirection()) {
            case OUTGOING:
                leftIsSource = true;
                break;
            case INCOMING:
                leftIsSource = false;
                break;
            case UNDIRECTED:
            default:
                leftIsSource = leftMapping.getLabel().equals(relMapping.getSourceLabel());
                break;
        }
        NodeMapping schemaSource = leftIsSource ? leftMapping : rightMapping;
        NodeMapping schemaSink = leftIsSource ? rightMapping : leftMapping;
        validateEndpointLabels(rel, relMapping, schemaSource, schemaSink);

        String leftVar = requireVariable(leftAst, "left endpoint");
        String rightVar = requireVariable(rightAst, "right endpoint");
        String sourceVar = leftIsSource ? leftVar : rightVar;
        String sinkVar = leftIsSource ? rightVar : leftVar;
        String relVar = rel.getVariable().orElse(null);

        // Collect identity-property equals per side from inline + WHERE,
        // then build bindings once. Edge attribute equals collected the
        // same way for the relationship.
        Map<String,String> sourceIdEquals = new LinkedHashMap<>();
        Map<String,String> sinkIdEquals = new LinkedHashMap<>();
        Map<String,String> attrEquals = new LinkedHashMap<>();

        Optional<PropertiesExpression> sourceInline = leftIsSource ? leftAst.getProperties() : rightAst.getProperties();
        Optional<PropertiesExpression> sinkInline = leftIsSource ? rightAst.getProperties() : leftAst.getProperties();
        sourceInline.ifPresent(props -> harvestNodeIdentityEquals(props, schemaSource, sourceVar, sourceIdEquals));
        sinkInline.ifPresent(props -> harvestNodeIdentityEquals(props, schemaSink, sinkVar, sinkIdEquals));
        rel.getProperties().ifPresent(props -> harvestEdgePropertyEquals(props, relMapping, attrEquals));

        match.getWhere().ifPresent(where -> {
            for (Expression term : conjuncts(where)) {
                applyWhereTerm(term, sourceVar, schemaSource, sourceIdEquals, sinkVar, schemaSink, sinkIdEquals, relMapping, relVar, attrEquals);
            }
        });

        NodeBinding sourceBinding = new NodeBinding(sourceVar, schemaSource, sourceIdEquals);
        NodeBinding sinkBinding = new NodeBinding(sinkVar, schemaSink, sinkIdEquals);

        HopSpec hop = new HopSpec(sourceBinding, sinkBinding, relMapping, rel.getDirection(), relVar, attrEquals);

        List<Projection> projections = buildProjections(ret, sourceBinding, sinkBinding, relMapping, relVar);
        Long limit = readLimit(ret);

        return new CypherPlan(hop, projections, limit, schema.getVersion());
    }

    // ---------- helpers --------------------------------------------------

    private MatchClause singleMatch(SingleQuery sq) {
        List<ReadingClause> reading = sq.getReadingClauses();
        MatchClause only = null;
        for (ReadingClause rc : reading) {
            if (rc instanceof WithClause) {
                throw new CypherUnsupportedException("WITH is deferred to M2");
            }
            if (rc instanceof MatchClause) {
                if (only != null) {
                    throw new CypherUnsupportedException("M1 supports a single MATCH clause; chained MATCHs are deferred to M2");
                }
                only = (MatchClause) rc;
            }
        }
        if (only == null) {
            throw new CypherUnsupportedException("query must contain a MATCH clause");
        }
        return only;
    }

    private NodeMapping resolveNodeMapping(NodePattern node, String which) {
        if (node.getLabels().size() != 1) {
            throw new CypherUnsupportedException(which + ": exactly one label is required in M1, got " + node.getLabels());
        }
        String label = node.getLabels().get(0);
        return schema.getNode(label).orElseThrow(() -> new CypherUnsupportedException(which + ": label not in graph schema: " + label));
    }

    private String requireVariable(NodePattern node, String which) {
        return node.getVariable()
                        .orElseThrow(() -> new CypherUnsupportedException(which + ": anonymous nodes are not supported in M1; bind a variable like (a:Label)"));
    }

    private void validateEndpointLabels(RelationshipPattern rel, RelMapping relMapping, NodeMapping schemaSource, NodeMapping schemaSink) {
        boolean direct = relMapping.getSourceLabel().equals(schemaSource.getLabel()) && relMapping.getSinkLabel().equals(schemaSink.getLabel());
        boolean swapped = relMapping.getSourceLabel().equals(schemaSink.getLabel()) && relMapping.getSinkLabel().equals(schemaSource.getLabel());
        boolean ok = direct || (rel.getDirection() == RelationshipPattern.Direction.UNDIRECTED && swapped);
        if (!ok) {
            throw new CypherUnsupportedException("relationship " + relMapping.getCypherType() + " endpoint labels do not match the pattern: schema expects ("
                            + relMapping.getSourceLabel() + ")-[:" + relMapping.getCypherType() + "]-(" + relMapping.getSinkLabel() + ")");
        }
    }

    private void harvestNodeIdentityEquals(PropertiesExpression props, NodeMapping mapping, String var, Map<String,String> sink) {
        Map<String,String> equals = literalMapEquals(props, "node " + var);
        for (Map.Entry<String,String> e : equals.entrySet()) {
            if (!mapping.isIdentityProperty(e.getKey())) {
                throw new CypherUnsupportedException("node " + var + ": property '" + e.getKey()
                                + "' is not the identity property; non-identity node-property filters require shard enrichment, planned for M2");
            }
            sink.put(e.getKey(), e.getValue());
        }
    }

    private void harvestEdgePropertyEquals(PropertiesExpression props, RelMapping rel, Map<String,String> sink) {
        Map<String,String> equals = literalMapEquals(props, "relationship :" + rel.getCypherType());
        for (Map.Entry<String,String> e : equals.entrySet()) {
            if (!rel.resolveAttributeSlot(e.getKey()).isPresent()) {
                throw new CypherUnsupportedException("relationship " + rel.getCypherType() + ": property '" + e.getKey()
                                + "' is not mapped to an edge attribute slot in the graph schema");
            }
            sink.put(e.getKey(), e.getValue());
        }
    }

    private Map<String,String> literalMapEquals(PropertiesExpression props, String context) {
        Expression expr = props.getExpression();
        if (!(expr instanceof LiteralExpression) || ((LiteralExpression) expr).getKind() != LiteralExpression.Kind.MAP) {
            throw new CypherUnsupportedException(context + ": only inline literal maps {key: 'value'} are supported in M1");
        }
        Map<String,Expression> entries = ((LiteralExpression) expr).asMap();
        Map<String,String> out = new LinkedHashMap<>();
        for (Map.Entry<String,Expression> entry : entries.entrySet()) {
            out.put(entry.getKey(), literalString(entry.getValue(), context + " property '" + entry.getKey() + "'"));
        }
        return out;
    }

    private List<Expression> conjuncts(Expression where) {
        List<Expression> out = new ArrayList<>();
        addConjuncts(where, out);
        return out;
    }

    private void addConjuncts(Expression e, List<Expression> sink) {
        if (e instanceof BinaryExpression && ((BinaryExpression) e).getOperator() == BinaryExpression.Operator.AND) {
            addConjuncts(((BinaryExpression) e).getLeft(), sink);
            addConjuncts(((BinaryExpression) e).getRight(), sink);
        } else {
            sink.add(e);
        }
    }

    private void applyWhereTerm(Expression term, String sourceVar, NodeMapping sourceMapping, Map<String,String> sourceIdEquals, String sinkVar,
                    NodeMapping sinkMapping, Map<String,String> sinkIdEquals, RelMapping relMapping, String relVar, Map<String,String> attrEquals) {
        if (term instanceof UnaryExpression) {
            throw new CypherUnsupportedException("WHERE: NOT is deferred to M2");
        }
        if (term instanceof FunctionCallExpression) {
            throw new CypherUnsupportedException("WHERE: function calls are deferred to M2");
        }
        if (term instanceof LabelCheckExpression) {
            throw new CypherUnsupportedException("WHERE: explicit label checks (x:Label) inside WHERE are deferred to M2; declare labels in MATCH");
        }
        if (!(term instanceof BinaryExpression)) {
            throw new CypherUnsupportedException("WHERE: only equality terms are supported in M1");
        }
        BinaryExpression bin = (BinaryExpression) term;
        if (bin.getOperator() == BinaryExpression.Operator.OR) {
            throw new CypherUnsupportedException("WHERE: OR is deferred to M2");
        }
        if (bin.getOperator() != BinaryExpression.Operator.EQ) {
            throw new CypherUnsupportedException("WHERE: only '=' equality is supported in M1, got " + bin.getOperator().getSymbol());
        }
        Expression lhs = bin.getLeft();
        Expression rhs = bin.getRight();
        if (!(lhs instanceof PropertyExpression) && rhs instanceof PropertyExpression) {
            Expression tmp = lhs;
            lhs = rhs;
            rhs = tmp;
        }
        if (!(lhs instanceof PropertyExpression)) {
            throw new CypherUnsupportedException("WHERE: equality must reference a bound variable's property, e.g. a.name = 'X'");
        }
        PropertyExpression pe = (PropertyExpression) lhs;
        if (pe.getPropertyPath().size() != 1) {
            throw new CypherUnsupportedException("WHERE: nested property paths are deferred to M2");
        }
        if (!(pe.getTarget() instanceof VariableExpression)) {
            throw new CypherUnsupportedException("WHERE: property access must be on a bound variable directly");
        }
        String var = ((VariableExpression) pe.getTarget()).getName();
        String prop = pe.getPropertyPath().get(0);
        String value = literalString(rhs, "WHERE term " + var + "." + prop);

        if (var.equals(sourceVar)) {
            requireIdentity(sourceMapping, sourceVar, prop);
            sourceIdEquals.put(prop, value);
        } else if (var.equals(sinkVar)) {
            requireIdentity(sinkMapping, sinkVar, prop);
            sinkIdEquals.put(prop, value);
        } else if (relVar != null && var.equals(relVar)) {
            if (!relMapping.resolveAttributeSlot(prop).isPresent()) {
                throw new CypherUnsupportedException(
                                "WHERE: relationship property '" + prop + "' is not mapped to an edge attribute slot in the graph schema");
            }
            attrEquals.put(prop, value);
        } else {
            throw new CypherUnsupportedException("WHERE: variable '" + var + "' is not bound by the MATCH pattern");
        }
    }

    private void requireIdentity(NodeMapping mapping, String var, String prop) {
        if (!mapping.isIdentityProperty(prop)) {
            throw new CypherUnsupportedException("WHERE: '" + var + "." + prop
                            + "' is not the identity property; non-identity node-property filters require shard enrichment, planned for M2");
        }
    }

    private Long readLimit(ReturnClause ret) {
        if (ret.isDistinct()) {
            throw new CypherUnsupportedException("RETURN DISTINCT is deferred to M2");
        }
        if (!ret.getOrderBy().isEmpty()) {
            throw new CypherUnsupportedException("ORDER BY is deferred to M2");
        }
        if (ret.getSkip().isPresent()) {
            throw new CypherUnsupportedException("SKIP is deferred to M2");
        }
        if (ret.isProjectAll()) {
            throw new CypherUnsupportedException("RETURN * is deferred to M2");
        }
        if (ret.getProjections().isEmpty()) {
            throw new CypherUnsupportedException("RETURN must list at least one projection");
        }
        if (!ret.getLimit().isPresent()) {
            return null;
        }
        Expression limitExpr = ret.getLimit().get();
        if (!(limitExpr instanceof LiteralExpression) || ((LiteralExpression) limitExpr).getKind() != LiteralExpression.Kind.INTEGER) {
            throw new CypherUnsupportedException("LIMIT must be an integer literal in M1");
        }
        long lim = (Long) ((LiteralExpression) limitExpr).getValue();
        if (lim < 0) {
            throw new IllegalArgumentException("LIMIT must be non-negative");
        }
        return lim;
    }

    private List<Projection> buildProjections(ReturnClause ret, NodeBinding source, NodeBinding sink, RelMapping rel, String relVar) {
        List<Projection> out = new ArrayList<>();
        Set<String> seenAlias = new LinkedHashSet<>();
        for (ProjectionItem item : ret.getProjections()) {
            Expression expr = item.getExpression();
            if (!(expr instanceof PropertyExpression)) {
                throw new CypherUnsupportedException("RETURN: only property projections (variable.property) are supported in M1");
            }
            PropertyExpression pe = (PropertyExpression) expr;
            if (pe.getPropertyPath().size() != 1) {
                throw new CypherUnsupportedException("RETURN: nested property paths are deferred to M2");
            }
            if (!(pe.getTarget() instanceof VariableExpression)) {
                throw new CypherUnsupportedException("RETURN: property access must be on a bound variable directly");
            }
            String var = ((VariableExpression) pe.getTarget()).getName();
            String prop = pe.getPropertyPath().get(0);
            String alias = item.getExposedName().orElseThrow(() -> new CypherUnsupportedException("RETURN: projection requires an alias (use AS)"));
            if (!seenAlias.add(alias)) {
                throw new CypherUnsupportedException("RETURN: duplicate projection alias '" + alias + "'");
            }
            Projection.Kind kind;
            if (var.equals(source.getVariable()) || var.equals(sink.getVariable())) {
                NodeBinding b = var.equals(source.getVariable()) ? source : sink;
                if (!b.getMapping().isIdentityProperty(prop)) {
                    throw new CypherUnsupportedException("RETURN: '" + var + "." + prop
                                    + "' is not the identity property; non-identity node-property projections require shard enrichment, planned for M2");
                }
                kind = Projection.Kind.NODE_PROPERTY;
            } else if (relVar != null && var.equals(relVar)) {
                if (!rel.resolveAttributeSlot(prop).isPresent()) {
                    throw new CypherUnsupportedException("RETURN: relationship property '" + prop + "' is not mapped to an edge attribute slot");
                }
                kind = Projection.Kind.REL_PROPERTY;
            } else {
                throw new CypherUnsupportedException("RETURN: variable '" + var + "' is not bound by the MATCH pattern");
            }
            out.add(new Projection(alias, var, prop, kind));
        }
        if (out.isEmpty()) {
            throw new CypherUnsupportedException("RETURN must produce at least one column");
        }
        return out;
    }

    private static String literalString(Expression e, String context) {
        if (!(e instanceof LiteralExpression)) {
            throw new CypherUnsupportedException(context + ": value must be a literal in M1");
        }
        LiteralExpression lit = (LiteralExpression) e;
        switch (lit.getKind()) {
            case STRING:
                return (String) lit.getValue();
            case INTEGER:
                return Long.toString((long) lit.getValue());
            case DECIMAL:
                return Double.toString((double) lit.getValue());
            case BOOLEAN:
                return Boolean.toString((boolean) lit.getValue());
            default:
                throw new CypherUnsupportedException(context + ": value kind " + lit.getKind() + " is not supported in M1");
        }
    }

    /** Visible for testing. */
    public GraphSchema getSchema() {
        return schema;
    }

    /**
     * The JEXL field name an EdgeFilterIterator-evaluated expression must
     * use to reach a given edge attribute slot. EdgeFilterIterator's
     * JexlContext is keyed by {@code EdgeModelFields.FieldKey} enum names
     * (e.g. {@code EDGE_ATTRIBUTE2}), lowercased; the iterator also
     * lowercases the JEXL string before evaluation so case here is
     * informational only.
     */
    public static String edgeAttributeFieldName(EdgeAttributeSlot slot) {
        switch (slot) {
            case ATTRIBUTE1:
                return "EDGE_ATTRIBUTE1";
            case ATTRIBUTE2:
                return "EDGE_ATTRIBUTE2";
            case ATTRIBUTE3:
                return "EDGE_ATTRIBUTE3";
            default:
                throw new IllegalStateException("unexpected slot " + slot);
        }
    }
}
