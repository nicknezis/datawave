package datawave.query.cypher.mapping;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.context.support.FileSystemXmlApplicationContext;

/**
 * Loads a {@link GraphSchema} from a Spring XML descriptor.
 * <p>
 * The descriptor declares {@link NodeMapping} beans and {@link RelMapping} beans; this loader collects all such beans from the context and assembles a
 * {@code GraphSchema}. Following the {@code DefaultEdgeModelFieldsFactory} convention, the descriptor location can be overridden by the
 * {@code cypher.graph.schema.path} system property pointing at an absolute file URL — useful for local development and IDE debugging.
 */
public final class GraphSchemaLoader {

    public static final String SYSTEM_PROPERTY = "cypher.graph.schema.path";

    private static final AtomicLong VERSION_COUNTER = new AtomicLong();

    private GraphSchemaLoader() {}

    /**
     * Loads from the given classpath resource (typically {@code config/cypher-graph-schema.xml}) or from the override file URL named by
     * {@link #SYSTEM_PROPERTY}.
     */
    public static GraphSchema load(String classpathResource) {
        String override = System.getProperty(SYSTEM_PROPERTY);
        if (override != null && !override.isEmpty()) {
            try (FileSystemXmlApplicationContext ctx = new FileSystemXmlApplicationContext(override)) {
                return assemble(ctx);
            }
        }
        try (ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext(classpathResource)) {
            return assemble(ctx);
        }
    }

    private static GraphSchema assemble(org.springframework.context.ApplicationContext ctx) {
        Map<String,NodeMapping> nodeBeans = ctx.getBeansOfType(NodeMapping.class);
        Map<String,RelMapping> relBeans = ctx.getBeansOfType(RelMapping.class);
        long version = VERSION_COUNTER.incrementAndGet();
        return new GraphSchema(version, nodeBeans.values(), relBeans.values());
    }
}
