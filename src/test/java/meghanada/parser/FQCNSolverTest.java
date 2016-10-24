package meghanada.parser;

import meghanada.GradleTestBase;
import meghanada.parser.source.JavaSource;
import meghanada.reflect.asm.CachedASMReflector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static meghanada.config.Config.timeIt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class FQCNSolverTest extends GradleTestBase {

    @org.junit.BeforeClass
    public static void beforeClass() throws Exception {
        GradleTestBase.setupReflector();
        CachedASMReflector cachedASMReflector = CachedASMReflector.getInstance();
        cachedASMReflector.addClasspath(getOutputDir());
        cachedASMReflector.createClassIndexes();
    }

    @Before
    public void setUp() throws Exception {

    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void resolveFQCN1() throws Exception {
        JavaParser parser = new JavaParser();
        assertNotNull(parser);
        FQCNSolver resolver = FQCNSolver.getInstance();
        {
            final JavaSource source = parser.parse(new File("./src/main/java/meghanada/reflect/asm/ASMReflector.java"));
            String fqcn = timeIt(() -> resolver.solveFQCN("log", source).get());
            assertEquals("org.apache.logging.log4j.Logger", fqcn);
        }
        {
            final JavaSource source = parser.parse(new File("./src/main/java/meghanada/reflect/asm/ASMReflector.java"));
            String fqcn = timeIt(() -> resolver.solveFQCN("this", source).get());
            assertEquals("meghanada.reflect.asm.ASMReflector", fqcn);
        }
    }

    @Test
    public void resolveFQCN2() throws Exception {
        JavaParser parser = new JavaParser();
        assertNotNull(parser);
        JavaSource source = parser.parse(new File("./src/main/java/meghanada/reflect/asm/ASMReflector.java"));
        assertNotNull(source);
        FQCNSolver resolver = FQCNSolver.getInstance();
        {
            String fqcn = timeIt(() -> resolver.solveFQCN("Map$Entry<String, Long>", source).get());
            assertEquals("java.util.Map$Entry<String, Long>", fqcn);
        }
    }

    @Test
    public void resolveFQCN3() throws Exception {
        JavaParser parser = new JavaParser();
        assertNotNull(parser);
        JavaSource source = parser.parse(new File("./src/main/java/meghanada/reflect/asm/ASMReflector.java"));
        assertNotNull(source);
        String fqcn = timeIt(() -> {
            FQCNSolver resolver = FQCNSolver.getInstance();
            return resolver.solveFQCN("Map.Entry<String, Long>", source).orElse("");
        });
        assertEquals("java.util.Map$Entry<String, Long>", fqcn);
    }

    @Test
    public void resolveFQCN4() throws Exception {
        JavaParser parser = new JavaParser();
        assertNotNull(parser);
        JavaSource source = parser.parse(new File("./src/main/java/meghanada/reflect/asm/ASMReflector.java"));
        assertNotNull(source);
        FQCNSolver resolver = FQCNSolver.getInstance();
        {
            String fqcn = timeIt(() -> resolver.solveFQCN("Map$Entry", source).get());
            assertEquals("java.util.Map$Entry", fqcn);
        }
    }

    @Test
    public void resolveFQCN5() throws Exception {
        JavaParser parser = new JavaParser();
        assertNotNull(parser);
        JavaSource source = parser.parse(new File("./src/main/java/meghanada/reflect/asm/ASMReflector.java"));
        assertNotNull(source);
        FQCNSolver resolver = FQCNSolver.getInstance();
        {
            String fqcn = timeIt(() -> resolver.solveFQCN("Map.Entry", source).get());
            assertEquals("java.util.Map$Entry", fqcn);
        }
    }

}
