package meghanada.utils;

import meghanada.GradleTestBase;
import org.junit.Test;

import static meghanada.config.Config.timeIt;
import static meghanada.config.Config.traceIt;
import static org.junit.Assert.assertEquals;

public class ClassNameTest extends GradleTestBase {


    @org.junit.BeforeClass
    public static void beforeClass() throws Exception {
        GradleTestBase.setupReflector();
    }

    @Test
    public void getName1() throws Exception {
        ClassName className1 = new ClassName("Map.Entry<String, Long>");
        String name1 = className1.getName();
        assertEquals("Map.Entry", name1);

        ClassName className2 = new ClassName("Map$Entry<String, Long>");
        String name2 = className2.getName();
        assertEquals("Map$Entry", name2);

        ClassName className3 = new ClassName("Map<String, Long>$Entry<String, Long>");
        String name3 = timeIt(() -> className3.getName());
        assertEquals("Map$Entry", name3);
    }

    @Test
    public void getName2() throws Exception {
        ClassName className1 = new ClassName("Map.Entry<String, Long>[]");
        String name1 = className1.getName();
        assertEquals("Map.Entry", name1);

        ClassName className2 = new ClassName("Map$Entry<String, Long>[]");
        String name2 = className2.getName();
        assertEquals("Map$Entry", name2);
    }

    @Test
    public void getName3() throws Exception {
        ClassName className1 = new ClassName("Map<K, V>.Entry");
        String name1 = className1.getName();
        assertEquals("Map.Entry", name1);

        ClassName className2 = new ClassName("Map<K, V>$Entry");
        String name2 = className2.getName();
        assertEquals("Map$Entry", name2);
    }

    @Test
    public void getName4() throws Exception {
        ClassName className = new ClassName("ThreadLocal<int[]>");
        String name = className.getName();
        assertEquals("ThreadLocal", name);
    }

    @Test
    public void getName5() throws Exception {
        ClassName className = new ClassName("Map<Object[], Void>");
        String name = className.getName();
        assertEquals("Map", name);
    }

    @Test
    public void getName6() throws Exception {
        ClassName className = new ClassName("meghanada.ManyInnerClass<K, V>$A");
        String name = className.getName();
        final String replaceClassName = className.replaceClassName("meghanada.ManyInnerClass<K, V>$A");
        assertEquals("meghanada.ManyInnerClass$A", name);
    }

    @Test
    public void replaceClassName1() throws Exception {
        ClassName className = new ClassName("Map.Entry");
        String name = className.replaceClassName("java.util.Map.Entry");
        assertEquals("java.util.Map.Entry", name);
    }

    @Test
    public void replaceClassName2() throws Exception {
        ClassName className1 = new ClassName("Map.Entry<String, Long>");
        String name1 = className1.replaceClassName("java.util.Map.Entry");
        assertEquals("java.util.Map.Entry<String, Long>", name1);

        ClassName className2 = new ClassName("Map$Entry<String, Long>");
        String name2 = traceIt(() -> className2.replaceClassName("java.util.Map$Entry"));
        assertEquals("java.util.Map$Entry<String, Long>", name2);
    }

    @Test
    public void replaceClassName3() throws Exception {
        ClassName className1 = new ClassName("Map.Entry<String, Long>[]");
        String name1 = className1.replaceClassName("java.util.Map.Entry");
        assertEquals("java.util.Map.Entry<String, Long>[]", name1);

        ClassName className2 = new ClassName("Map$Entry<String, Long>[]");
        String name2 = className2.replaceClassName("java.util.Map$Entry");
        assertEquals("java.util.Map$Entry<String, Long>[]", name2);
    }

    @Test
    public void replaceClassName4() throws Exception {
        ClassName className = new ClassName("Map.Entry[]");
        String name = className.replaceClassName("java.util.Map.Entry");
        assertEquals("java.util.Map.Entry[]", name);
    }

    @Test
    public void replaceClassName5() throws Exception {
        ClassName className = new ClassName("SelfRef1<java.lang.Object>$Ref");
        final String simpleName = className.getName();
        assertEquals("SelfRef1$Ref", simpleName);
        String replaced = className.replaceClassName("meghanada." + simpleName);
        assertEquals("meghanada.SelfRef1<java.lang.Object>$Ref", replaced);
    }

    @Test
    public void replaceClassName6() throws Exception {
        ClassName className = new ClassName("A<java.lang.Object>$B<java.lang.String>");
        final String simpleName = className.getName();
        assertEquals("A$B", simpleName);
        String replaced = className.replaceClassName("meghanada." + simpleName);
        assertEquals("meghanada.A<java.lang.Object>$B<java.lang.String>", replaced);
    }

    @Test
    public void replaceClassName7() throws Exception {
        ClassName className = new ClassName("A$B<java.lang.String>");
        final String simpleName = className.getName();
        assertEquals("A$B", simpleName);
        String replaced = className.replaceClassName("meghanada." + simpleName);
        assertEquals("meghanada.A$B<java.lang.String>", replaced);
    }

    @Test
    public void replaceClassName8() throws Exception {
        ClassName className = new ClassName("A<java.lang.Long, java.lang.Integer>$B<java.lang.Long, java.lang.Integer>");
        final String simpleName = className.getName();
        assertEquals("A$B", simpleName);
        String replaced = className.replaceClassName("meghanada." + simpleName);
        assertEquals("meghanada.A<java.lang.Long, java.lang.Integer>$B<java.lang.Long, java.lang.Integer>", replaced);
    }

    @Test
    public void toFQCN1() throws Exception {
        ClassName className = new ClassName("Map");
        String fqcn = className.toFQCN(null, null);
        assertEquals("java.util.Map", fqcn);
    }

}