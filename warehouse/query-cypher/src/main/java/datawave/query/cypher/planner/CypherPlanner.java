package datawave.query.cypher.planner;

import java.util.ArrayList;
import java.util.Collections;
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
import datawave.query.cypher.ast.SortItem;
import datawave.query.cypher.ast.UnaryExpression;
import datawave.query.cypher.ast.VariableExpression;
import datawave.query.cypher.ast.WithClause;
import datawave.query.cypher.mapping.EdgeAttributeSlot;
import datawave.query.cypher.mapping.GraphSchema;
import datawave.query.cypher.mapping.NodeMapping;
import datawave.query.cypher.mapping.RelMapping;

/**
 * Translates a parsed + semantically-analyzed Cypher query into a
 * {@link CypherPlan} that the executor can run.
 *
 * <p>M2 surface (all M1 features plus):
 * <ul>
 *   <li>Fixed-length multi-hop patterns:
 *       {@code (a)-[r1]->(b)-[r2]->(c)}.</li>
 *   <li>WITH-chained MATCH clauses:
 *       {@code MATCH (a)-[r1]->(b) WITH a,b MATCH (b)-[r2]->(c) RETURN …}.</li>
 *   <li>RETURN ORDER BY (in-memory sort on projected alias), SKIP, DISTINCT.</li>
 *   <li>RETURN * (expands to all bound node identity projections).</li>
 *   <li>Non-identity node properties in WHERE (equality) and RETURN —
 *       recorded as {@link Projection.Kind#NODE_SHARD_PROPERTY} and evaluated
 *       post-enrichment by {@link datawave.query.cypher.executor.ShardEnrichmentService}.</li>
 * </ul>
 *
 * <p>Still deferred (with precise rejection messages):
 * <ul>
 *   <li>Variable-length relationships {@code [*lo..hi]} — M3.</li>
 *   <li>Aggregation functions — M3.</li>
 *   <li>Path variables {@code p = …} — M3.</li>
 *   <li>UNION — unsupported.</li>
 *   <li>OPTIONAL MATCH — unsupported.</li>
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
            throw new CypherUnsupportedException("UNION is not supported");
        }
        SingleQuery sq = ast.getBranches().get(0);

        ReturnClause ret = sq.getReturnClause()
                        .orElseThrow(() -> new CypherUnsupportedException("RETURN clause is required; queries without RETURN are not supported"));

        // Per-node context, keyed by Cypher variable name.
        // Shared across all MATCHes so junction variables reuse the same binding.
        Map<String,NodeContext> nodeContexts = new LinkedHashMap<>();
        // Rel variable → its RelMapping, for projection resolution.
        Map<String,RelMapping> relContexts = new LinkedHashMap<>();
        // Ordered list of hop specs built from all MATCH patterns.
        List<HopSpec> allHops = new ArrayList<>();

        processReadingClauses(sq.getReadingClauses(), nodeContexts, relContexts, allHops);

        if (allHops.isEmpty()) {
            throw new CypherUnsupportedException("query must contain at least one MATCH clause with a relationship pattern");
        }

        List<Projection> projections = buildProjections(ret, nodeContexts, relContexts, allHops);
        Long limit = readLimitValue(ret);
        Long skip = readSkipValue(ret);
        boolean distinct = ret.isDistinct();
        List<SortSpec> orderBy = buildOrderBy(ret, projections);

        return new CypherPlan(allHops, projections, limit, skip, distinct, orderBy, schema.getVersion());
    }

    // ---- reading-clause processing --------------------------------------

    private void processReadingClauses(List<ReadingClause> clauses, Map<String,NodeContext> nodeContexts,
                    Map<String,RelMapping> relContexts, List<HopSpec> allHops) {
        for (ReadingClause rc : clauses) {
            if (rc instanceof MatchClause) {
                processMatchClause((MatchClause) rc, nodeContexts, relContexts, allHops);
            } else if (rc instanceof WithClause) {
                processWithClause((WithClause) rc, nodeContexts);
            }
        }
    }

    private void processMatchClause(MatchClause match, Map<String,NodeContext> nodeContexts,
                    Map<String,RelMapping> relContexts, List<HopSpec> allHops) {
        if (match.isOptional()) {
            throw new CypherUnsupportedException("OPTIONAL MATCH is not supported");
        }
        if (match.getPatterns().size() != 1) {
            throw new CypherUnsupportedException("M2 supports exactly one pattern per MATCH; multiple comma-separated patterns are deferred to M3");
        }
        Pattern pattern = match.getPatterns().get(0);
        if (pattern.getPathVariable().isPresent()) {
            throw new CypherUnsupportedException("path variables (p = ...) are deferred to M3");
        }

        PatternElement element = pattern.getElement();
        List<NodePattern> nodes = element.getNodes();
        List<RelationshipPattern> rels = element.getRelationships();

        if (rels.isEmpty()) {
            throw new CypherUnsupportedException("MATCH pattern must contain at least one relationship");
        }

        // Register each node variable; junction variables reuse existing contexts.
        for (NodePattern np : nodes) {
            String var = requireVariable(np, "node in pattern");
            if (!nodeContexts.containsKey(var)) {
                NodeMapping mapping = resolveNodeMapping(np, "node " + var);
                nodeContexts.put(var, new NodeContext(var, mapping));
            } else if (!np.getLabels().isEmpty()) {
                // Variable re-used in a second MATCH — validate label consistency.
                NodeMapping existing = nodeContexts.get(var).mapping;
                NodeMapping reresolved = resolveNodeMapping(np, "node " + var);
                if (!existing.getLabel().equals(reresolved.getLabel())) {
                    throw new CypherUnsupportedException("variable '" + var + "' is re-used with a different label");
                }
            }
            // Harvest inline property filters for this occurrence.
            np.getProperties().ifPresent(props -> {
                NodeContext ctx = nodeContexts.get(var);
                harvestNodePropertyFilters(props, ctx);
            });
        }

        // Build one HopSpec per relationship.
        int hopStartIdx = allHops.size();
        for (int i = 0; i < rels.size(); i++) {
            RelationshipPattern rel = rels.get(i);
            NodePattern leftAst = nodes.get(i);
            NodePattern rightAst = nodes.get(i + 1);

            if (rel.isVariableLength()) {
                throw new CypherUnsupportedException("variable-length relationships [*lo..hi] are deferred to M3");
            }
            if (rel.getTypes().size() != 1) {
                throw new CypherUnsupportedException("exactly one relationship type is required per hop, e.g. -[:KNOWS]-");
            }

            String relType = rel.getTypes().get(0);
            RelMapping relMapping = schema.getRelationship(relType)
                            .orElseThrow(() -> new CypherUnsupportedException("relationship type not in graph schema: " + relType));

            String leftVar = requireVariable(leftAst, "left endpoint");
            String rightVar = requireVariable(rightAst, "right endpoint");
            NodeContext leftCtx = nodeContexts.get(leftVar);
            NodeContext rightCtx = nodeContexts.get(rightVar);

            // Orient source/sink per schema + pattern direction.
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
                    leftIsSource = leftCtx.mapping.getLabel().equals(relMapping.getSourceLabel());
                    break;
            }

            NodeContext sourceCtx = leftIsSource ? leftCtx : rightCtx;
            NodeContext sinkCtx = leftIsSource ? rightCtx : leftCtx;
            validateEndpointLabels(rel, relMapping, sourceCtx.mapping, sinkCtx.mapping);

            String relVar = rel.getVariable().orElse(null);
            Map<String,String> attrEquals = new LinkedHashMap<>();
            rel.getProperties().ifPresent(props -> harvestEdgePropertyEquals(props, relMapping, attrEquals));

            if (relVar != null) {
                relContexts.put(relVar, relMapping);
            }

            NodeBinding sourceBinding = buildNodeBinding(sourceCtx);
            NodeBinding sinkBinding = buildNodeBinding(sinkCtx);
            allHops.add(new HopSpec(sourceBinding, sinkBinding, relMapping, rel.getDirection(), relVar, attrEquals));
        }

        // Apply WHERE to populate identity / shard filters on node contexts.
        match.getWhere().ifPresent(where -> {
            Set<String> matchVars = new LinkedHashSet<>();
            for (NodePattern np : nodes) {
                np.getVariable().ifPresent(matchVars::add);
            }
            for (RelationshipPattern rel : rels) {
                rel.getVariable().ifPresent(matchVars::add);
            }
            for (Expression term : conjuncts(where)) {
                applyWhereTerm(term, nodeContexts, relContexts, matchVars, allHops);
            }
        });

        // Rebuild NodeBindings for all hops added in this MATCH so that WHERE-clause
        // node filters (identityEquals / shardFilters set above) are reflected.
        // addAttrEqualToHop already rebuilds HopSpec for rel-attribute filters, so
        // here we only need to refresh the node-side bindings.
        for (int i = hopStartIdx; i < allHops.size(); i++) {
            HopSpec h = allHops.get(i);
            NodeBinding newSource = buildNodeBinding(nodeContexts.get(h.getSource().getVariable()));
            NodeBinding newSink = buildNodeBinding(nodeContexts.get(h.getSink().getVariable()));
            allHops.set(i, new HopSpec(newSource, newSink, h.getRel(), h.getPatternDirection(), h.getRelVariable().orElse(null), h.getAttributeEquals()));
        }
    }

    private void processWithClause(WithClause with, Map<String,NodeContext> nodeContexts) {
        // WITH WHERE can add additional filters to variables still in scope.
        with.getWhere().ifPresent(where -> {
            Set<String> withVars = new LinkedHashSet<>();
            for (ProjectionItem item : with.getProjections()) {
                item.getExposedName().ifPresent(withVars::add);
            }
            for (Expression term : conjuncts(where)) {
                applyWithWhereTerm(term, nodeContexts, withVars);
            }
        });
        // Note: WITH ORDER BY / SKIP / LIMIT inside WITH are validated by the
        // semantic analyzer but not executed separately in M2; they do not affect
        // the logical plan (ORDER BY and LIMIT on the final RETURN dominate).
    }

    // ---- node context helpers -------------------------------------------

    private static class NodeContext {
        final String variable;
        final NodeMapping mapping;
        final Map<String,String> identityEquals = new LinkedHashMap<>();
        final Map<String,String> shardFilters = new LinkedHashMap<>();

        NodeContext(String variable, NodeMapping mapping) {
            this.variable = variable;
            this.mapping = mapping;
        }
    }

    private NodeBinding buildNodeBinding(NodeContext ctx) {
        return new NodeBinding(ctx.variable, ctx.mapping, ctx.identityEquals, ctx.shardFilters);
    }

    private void harvestNodePropertyFilters(PropertiesExpression props, NodeContext ctx) {
        Map<String,String> parsed = literalMapEquals(props, "node " + ctx.variable);
        for (Map.Entry<String,String> e : parsed.entrySet()) {
            if (ctx.mapping.isIdentityProperty(e.getKey())) {
                ctx.identityEquals.put(e.getKey(), e.getValue());
            } else {
                // Non-identity inline property — validate it's in the schema before storing.
                if (!ctx.mapping.getProperties().containsKey(e.getKey())) {
                    throw new CypherUnsupportedException("node " + ctx.variable + ": property '" + e.getKey()
                                    + "' is not defined in the graph schema for label '" + ctx.mapping.getLabel() + "'");
                }
                ctx.shardFilters.put(e.getKey(), e.getValue());
            }
        }
    }

    private void harvestEdgePropertyEquals(PropertiesExpression props, RelMapping rel, Map<String,String> sink) {
        Map<String,String> parsed = literalMapEquals(props, "relationship :" + rel.getCypherType());
        for (Map.Entry<String,String> e : parsed.entrySet()) {
            if (!rel.resolveAttributeSlot(e.getKey()).isPresent()) {
                throw new CypherUnsupportedException("relationship " + rel.getCypherType() + ": property '" + e.getKey()
                                + "' is not mapped to an edge attribute slot in the graph schema");
            }
            sink.put(e.getKey(), e.getValue());
        }
    }

    // ---- WHERE processing -----------------------------------------------

    private void applyWhereTerm(Expression term, Map<String,NodeContext> nodeContexts, Map<String,RelMapping> relContexts,
                    Set<String> matchVars, List<HopSpec> hops) {
        if (term instanceof UnaryExpression) {
            throw new CypherUnsupportedException("WHERE: NOT is not supported in M2");
        }
        if (term instanceof FunctionCallExpression) {
            throw new CypherUnsupportedException("WHERE: function calls are not supported in M2");
        }
        if (term instanceof LabelCheckExpression) {
            throw new CypherUnsupportedException("WHERE: explicit label checks (x:Label) inside WHERE are not supported; declare labels in MATCH");
        }
        if (!(term instanceof BinaryExpression)) {
            throw new CypherUnsupportedException("WHERE: unsupported expression type; equality terms are required");
        }
        BinaryExpression bin = (BinaryExpression) term;
        if (bin.getOperator() == BinaryExpression.Operator.OR) {
            throw new CypherUnsupportedException("WHERE: OR is not supported in M2");
        }
        if (bin.getOperator() != BinaryExpression.Operator.EQ) {
            throw new CypherUnsupportedException("WHERE: only '=' equality is supported in M2, got " + bin.getOperator().getSymbol());
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
            throw new CypherUnsupportedException("WHERE: nested property paths are not supported in M2");
        }
        if (!(pe.getTarget() instanceof VariableExpression)) {
            throw new CypherUnsupportedException("WHERE: property access must be on a bound variable directly");
        }
        String var = ((VariableExpression) pe.getTarget()).getName();
        String prop = pe.getPropertyPath().get(0);
        String value = literalString(rhs, "WHERE term " + var + "." + prop);

        if (nodeContexts.containsKey(var)) {
            NodeContext ctx = nodeContexts.get(var);
            if (ctx.mapping.isIdentityProperty(prop)) {
                ctx.identityEquals.put(prop, value);
            } else {
                if (!ctx.mapping.getProperties().containsKey(prop)) {
                    throw new CypherUnsupportedException("WHERE: property '" + prop + "' is not defined in the schema for label '"
                                    + ctx.mapping.getLabel() + "'");
                }
                ctx.shardFilters.put(prop, value);
            }
        } else if (relContexts.containsKey(var)) {
            RelMapping relMapping = relContexts.get(var);
            if (!relMapping.resolveAttributeSlot(prop).isPresent()) {
                throw new CypherUnsupportedException("WHERE: relationship property '" + prop
                                + "' is not mapped to an edge attribute slot in the graph schema");
            }
            // Attribute filter goes into the hop for the matching rel variable.
            addAttrEqualToHop(hops, var, prop, value);
        } else {
            throw new CypherUnsupportedException("WHERE: variable '" + var + "' is not bound by the MATCH pattern");
        }
    }

    /** Finds the hop whose relVariable matches and appends the attribute equality. */
    private void addAttrEqualToHop(List<HopSpec> hops, String relVar, String prop, String value) {
        for (int i = 0; i < hops.size(); i++) {
            HopSpec h = hops.get(i);
            if (h.getRelVariable().filter(relVar::equals).isPresent()) {
                // HopSpec is immutable — rebuild it with the new attribute filter.
                Map<String,String> newAttrs = new LinkedHashMap<>(h.getAttributeEquals());
                newAttrs.put(prop, value);
                hops.set(i, new HopSpec(h.getSource(), h.getSink(), h.getRel(), h.getPatternDirection(), h.getRelVariable().orElse(null), newAttrs));
                return;
            }
        }
        throw new CypherUnsupportedException("WHERE: relationship variable '" + relVar + "' not found in any hop");
    }

    private void applyWithWhereTerm(Expression term, Map<String,NodeContext> nodeContexts, Set<String> withVars) {
        // WITH WHERE is validated by the semantic analyzer; we apply equality
        // filters on projected node variables the same way as MATCH WHERE.
        if (!(term instanceof BinaryExpression)) {
            return; // non-equality terms silently pass (semantic layer validated)
        }
        BinaryExpression bin = (BinaryExpression) term;
        if (bin.getOperator() != BinaryExpression.Operator.EQ) {
            return;
        }
        Expression lhs = bin.getLeft();
        Expression rhs = bin.getRight();
        if (!(lhs instanceof PropertyExpression) && rhs instanceof PropertyExpression) {
            Expression tmp = lhs;
            lhs = rhs;
            rhs = tmp;
        }
        if (!(lhs instanceof PropertyExpression)) {
            return;
        }
        PropertyExpression pe = (PropertyExpression) lhs;
        if (pe.getPropertyPath().size() != 1 || !(pe.getTarget() instanceof VariableExpression)) {
            return;
        }
        String var = ((VariableExpression) pe.getTarget()).getName();
        if (!nodeContexts.containsKey(var) || !withVars.contains(var)) {
            return;
        }
        String prop = pe.getPropertyPath().get(0);
        String value = literalString(rhs, "WITH WHERE " + var + "." + prop);
        NodeContext ctx = nodeContexts.get(var);
        if (ctx.mapping.isIdentityProperty(prop)) {
            ctx.identityEquals.put(prop, value);
        } else if (ctx.mapping.getProperties().containsKey(prop)) {
            ctx.shardFilters.put(prop, value);
        }
    }

    // ---- RETURN processing ----------------------------------------------

    private List<Projection> buildProjections(ReturnClause ret, Map<String,NodeContext> nodeContexts,
                    Map<String,RelMapping> relContexts, List<HopSpec> hops) {
        if (ret.isProjectAll()) {
            return buildProjectionsForStar(nodeContexts);
        }
        if (ret.getProjections().isEmpty()) {
            throw new CypherUnsupportedException("RETURN must list at least one projection");
        }
        List<Projection> out = new ArrayList<>();
        Set<String> seenAlias = new LinkedHashSet<>();
        for (ProjectionItem item : ret.getProjections()) {
            Expression expr = item.getExpression();
            if (!(expr instanceof PropertyExpression)) {
                throw new CypherUnsupportedException("RETURN: only property projections (variable.property) are supported in M2");
            }
            PropertyExpression pe = (PropertyExpression) expr;
            if (pe.getPropertyPath().size() != 1) {
                throw new CypherUnsupportedException("RETURN: nested property paths are not supported in M2");
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
            if (nodeContexts.containsKey(var)) {
                NodeMapping mapping = nodeContexts.get(var).mapping;
                kind = mapping.isIdentityProperty(prop) ? Projection.Kind.NODE_PROPERTY : Projection.Kind.NODE_SHARD_PROPERTY;
                if (kind == Projection.Kind.NODE_SHARD_PROPERTY && !mapping.getProperties().containsKey(prop)) {
                    throw new CypherUnsupportedException("RETURN: property '" + prop + "' is not defined in the schema for label '"
                                    + mapping.getLabel() + "'");
                }
            } else if (relContexts.containsKey(var)) {
                RelMapping relMapping = relContexts.get(var);
                if (!relMapping.resolveAttributeSlot(prop).isPresent()) {
                    throw new CypherUnsupportedException("RETURN: relationship property '" + prop + "' is not mapped to an edge attribute slot");
                }
                kind = Projection.Kind.REL_PROPERTY;
            } else {
                throw new CypherUnsupportedException("RETURN: variable '" + var + "' is not bound by any MATCH pattern");
            }
            out.add(new Projection(alias, var, prop, kind));
        }
        return out;
    }

    /** Expands {@code RETURN *} to all bound node identity projections. */
    private List<Projection> buildProjectionsForStar(Map<String,NodeContext> nodeContexts) {
        List<Projection> out = new ArrayList<>();
        for (NodeContext ctx : nodeContexts.values()) {
            String alias = ctx.variable + "_" + ctx.mapping.getIdentityProperty();
            out.add(new Projection(alias, ctx.variable, ctx.mapping.getIdentityProperty(), Projection.Kind.NODE_PROPERTY));
        }
        return out;
    }

    private Long readLimitValue(ReturnClause ret) {
        if (!ret.getLimit().isPresent()) {
            return null;
        }
        Expression limitExpr = ret.getLimit().get();
        if (!(limitExpr instanceof LiteralExpression) || ((LiteralExpression) limitExpr).getKind() != LiteralExpression.Kind.INTEGER) {
            throw new CypherUnsupportedException("LIMIT must be an integer literal in M2");
        }
        long lim = (Long) ((LiteralExpression) limitExpr).getValue();
        if (lim < 0) {
            throw new IllegalArgumentException("LIMIT must be non-negative");
        }
        return lim;
    }

    private Long readSkipValue(ReturnClause ret) {
        if (!ret.getSkip().isPresent()) {
            return null;
        }
        Expression skipExpr = ret.getSkip().get();
        if (!(skipExpr instanceof LiteralExpression) || ((LiteralExpression) skipExpr).getKind() != LiteralExpression.Kind.INTEGER) {
            throw new CypherUnsupportedException("SKIP must be an integer literal in M2");
        }
        long s = (Long) ((LiteralExpression) skipExpr).getValue();
        if (s < 0) {
            throw new IllegalArgumentException("SKIP must be non-negative");
        }
        return s;
    }

    private List<SortSpec> buildOrderBy(ReturnClause ret, List<Projection> projections) {
        if (ret.getOrderBy().isEmpty()) {
            return Collections.emptyList();
        }
        // Build alias → projection index for validation.
        Set<String> projAliases = new LinkedHashSet<>();
        for (Projection p : projections) {
            projAliases.add(p.getAlias());
        }
        List<SortSpec> out = new ArrayList<>();
        for (SortItem item : ret.getOrderBy()) {
            Expression sortExpr = item.getExpression();
            if (!(sortExpr instanceof VariableExpression)) {
                throw new CypherUnsupportedException("ORDER BY: only projected aliases are supported in M2, e.g. ORDER BY alias ASC");
            }
            String alias = ((VariableExpression) sortExpr).getName();
            if (!projAliases.contains(alias)) {
                throw new CypherUnsupportedException("ORDER BY: '" + alias + "' is not a projected alias in the RETURN clause");
            }
            out.add(new SortSpec(alias, item.getDirection()));
        }
        return out;
    }

    // ---- structural helpers --------------------------------------------

    private NodeMapping resolveNodeMapping(NodePattern node, String which) {
        if (node.getLabels().size() != 1) {
            throw new CypherUnsupportedException(which + ": exactly one label is required, got " + node.getLabels());
        }
        String label = node.getLabels().get(0);
        return schema.getNode(label).orElseThrow(() -> new CypherUnsupportedException(which + ": label not in graph schema: " + label));
    }

    private String requireVariable(NodePattern node, String which) {
        return node.getVariable().orElseThrow(
                        () -> new CypherUnsupportedException(which + ": anonymous nodes are not supported; bind a variable like (a:Label)"));
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

    private Map<String,String> literalMapEquals(PropertiesExpression props, String context) {
        Expression expr = props.getExpression();
        if (!(expr instanceof LiteralExpression) || ((LiteralExpression) expr).getKind() != LiteralExpression.Kind.MAP) {
            throw new CypherUnsupportedException(context + ": only inline literal maps {key: 'value'} are supported");
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

    private static String literalString(Expression e, String context) {
        if (!(e instanceof LiteralExpression)) {
            throw new CypherUnsupportedException(context + ": value must be a literal in M2");
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
                throw new CypherUnsupportedException(context + ": value kind " + lit.getKind() + " is not supported in M2");
        }
    }

    /** Visible for testing. */
    public GraphSchema getSchema() {
        return schema;
    }

    /**
     * The JEXL field name an EdgeFilterIterator-evaluated expression must
     * use to reach a given edge attribute slot.
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
