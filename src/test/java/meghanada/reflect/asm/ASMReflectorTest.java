package meghanada.reflect.asm;

import com.google.common.base.Strings;
import meghanada.GradleTestBase;
import meghanada.reflect.ClassIndex;
import meghanada.reflect.MemberDescriptor;
import meghanada.reflect.MethodDescriptor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static meghanada.config.Config.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ASMReflectorTest extends GradleTestBase {

    private static final Logger log = LogManager.getLogger(ASMReflectorTest.class);

    @Test
    public void testGetInstance() throws Exception {
        ASMReflector asmReflector = ASMReflector.getInstance();
        assertNotNull(asmReflector);
    }

    @Test
    public void testGetClasses1() throws Exception {
        File jar = getJar("junit");
        ASMReflector asmReflector = ASMReflector.getInstance();
        Map<ClassIndex, File> classIndex = timeIt(() -> asmReflector.getClasses(jar));
        assertEquals(189, classIndex.size());
//        classIndex.forEach((classIndex1, classFile) -> System.out.println(classIndex1));
    }

    @Test
    public void testGetClasses2() throws Exception {
        File jar = getRTJar();
        ASMReflector asmReflector = ASMReflector.getInstance();
        Map<ClassIndex, File> classIndex = timeIt(() -> asmReflector.getClasses(jar));
        assertEquals(4105, classIndex.size());
//        classIndex.forEach((classIndex1, classFile) -> System.out.println(classIndex1));
    }

    @Test
    public void testReflectInner1() throws Exception {
        ASMReflector asmReflector = ASMReflector.getInstance();
        {
            String fqcn = "java.util.Map$Entry";
            File jar = getRTJar();
            Map<ClassIndex, File> index = asmReflector.getClasses(jar);
            final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn);
            List<MemberDescriptor> memberDescriptors = asmReflector.reflectAll(info);
            assertEquals(18, memberDescriptors.size());
        }
    }

    @Test
    public void testReflectInner2() throws Exception {
        ASMReflector asmReflector = ASMReflector.getInstance();
        {
            String fqcn = "java.util.Map.Entry";
            File jar = getRTJar();
            Map<ClassIndex, File> index = asmReflector.getClasses(jar);
            final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn);

            List<MemberDescriptor> memberDescriptors = asmReflector.reflectAll(info);

            memberDescriptors.forEach(m -> log.info("{}", m.getDisplayDeclaration()));
            assertEquals(18, memberDescriptors.size());
        }

    }

    @Test
    public void testReflectWithGenerics1() throws Exception {
        ASMReflector asmReflector = ASMReflector.getInstance();
        String fqcn = "java.util.Map";
        File jar = getRTJar();
        Map<ClassIndex, File> index = asmReflector.getClasses(jar);
        final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn);

        List<MemberDescriptor> memberDescriptors = timeIt(() -> asmReflector.reflectAll(info));
        memberDescriptors.forEach(m -> log.info("{}", m.getDisplayDeclaration()));
        assertEquals(34, memberDescriptors.size());

        memberDescriptors.stream()
                .filter(memberDescriptor -> memberDescriptor.getName().equals("entrySet"))
                .forEach(memberDescriptor -> {
                    if (memberDescriptor instanceof MethodDescriptor) {
                        MethodDescriptor methodDescriptor = (MethodDescriptor) memberDescriptor;
                        methodDescriptor.getTypeParameterMap().put("K", "String");
                        methodDescriptor.getTypeParameterMap().put("V", "Long");
                        log.info("{}", memberDescriptor.getReturnType());
                        assertEquals("java.util.Set<java.util.Map.Entry<String, Long>>", memberDescriptor.getReturnType());
                    }
                });
    }

    @Test
    public void testReflectWithGenerics2() throws Exception {
        ASMReflector asmReflector = ASMReflector.getInstance();
        {
            String fqcn = "java.util.Enumeration<? extends ZipEntry>";
            File jar = getRTJar();
            Map<ClassIndex, File> index = asmReflector.getClasses(jar);
            final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn);

            List<MemberDescriptor> memberDescriptors = timeIt(() -> asmReflector.reflectAll(info));
            memberDescriptors.forEach(m -> log.trace("{}", m.getDisplayDeclaration()));
            assertEquals(13, memberDescriptors.size());

        }
    }

    @Test
    public void testReflectWithGenerics3() throws Exception {
        ASMReflector asmReflector = ASMReflector.getInstance();
        String fqcn = "java.util.Map<? extends String, ? extends Long>";
        File jar = getRTJar();
        Map<ClassIndex, File> index = asmReflector.getClasses(jar);
        final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn);

        List<MemberDescriptor> memberDescriptors = timeIt(() -> asmReflector.reflectAll(info));
        memberDescriptors.forEach(m -> log.info("{}", m.getDisplayDeclaration()));
        assertEquals(34, memberDescriptors.size());
    }

    @Test
    public void testReflectWithGenerics4() throws Exception {
        ASMReflector asmReflector = ASMReflector.getInstance();
        String fqcn = "com.google.common.cache.CacheBuilder<Object, Object>";
        File jar = getJar("guava");
        Map<ClassIndex, File> index = asmReflector.getClasses(jar);
        final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn);

        List<MemberDescriptor> memberDescriptors = asmReflector.reflectAll(info);
        memberDescriptors.forEach(m -> {
            log.info("{}", m.getDeclaration());
        });
        assertEquals(62, memberDescriptors.size());
    }

    @Test
    public void testReflectAll1() throws Exception {
        ASMReflector asmReflector = ASMReflector.getInstance();
        {
            File jar = getRTJar();
            Map<ClassIndex, File> index = asmReflector.getClasses(jar);

            String fqcn = "java.util.stream.Stream<java.util.List<java.lang.String>>";
            final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn);

            List<MemberDescriptor> memberDescriptors1 = timeIt(() -> asmReflector.reflectAll(info));
            assertEquals(58, memberDescriptors1.size());
            memberDescriptors1.forEach(md -> {
                log.info("{}", md.getDeclaration());
            });
            List<MemberDescriptor> memberDescriptors2 = timeIt(() -> asmReflector.reflectAll(info));
            assertEquals(58, memberDescriptors2.size());

        }
    }

    @Test
    public void testReflectAll2() throws Exception {
        ASMReflector asmReflector = ASMReflector.getInstance();
        {
            File jar = getRTJar();
            Map<ClassIndex, File> index = asmReflector.getClasses(jar);

            String fqcn = "java.lang.String";
            final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn);
            List<MemberDescriptor> md = timeIt(() -> asmReflector.reflectAll(info));
            assertEquals(100, md.size());
        }
    }

    @Test
    public void testReflectAll3() throws Exception {
        ASMReflector asmReflector = ASMReflector.getInstance();
        {
            File jar = getRTJar();
            Map<ClassIndex, File> index = asmReflector.getClasses(jar);

            String fqcn = "java.util.List";
            final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn);
            List<MemberDescriptor> memberDescriptors1 = timeIt(() -> {
                return asmReflector.reflectAll(info);
            });
            memberDescriptors1.sort((o1, o2) -> o1.getName().compareTo(o2.getName()));
            memberDescriptors1.forEach(memberDescriptor -> {
                log.info("{}", memberDescriptor.getDisplayDeclaration());
            });
            assertEquals(41, memberDescriptors1.size());
        }
    }

    @Test
    public void testReflectAll4() throws Exception {
        ASMReflector asmReflector = ASMReflector.getInstance();
        {
            File jar = getRTJar();
            Map<ClassIndex, File> index = asmReflector.getClasses(jar);

            String fqcn = "java.util.function.Predicate";
            final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn);
            List<MemberDescriptor> memberDescriptors = timeIt(() -> asmReflector.reflectAll(info));
            assertEquals(16, memberDescriptors.size());
            memberDescriptors.forEach(memberDescriptor -> log.trace(memberDescriptor.getDeclaration()));
        }

    }

    @Test
    public void testReflectAll5() throws Exception {
        ASMReflector asmReflector = ASMReflector.getInstance();
        {
            File jar = getRTJar();
            Map<ClassIndex, File> index = asmReflector.getClasses(jar);

            // Config.load().setDebug();
            String fqcn = "java.util.stream.Stream<java.util.stream.Stream<java.util.List<java.lang.String>>>";
            final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn);
            List<MemberDescriptor> memberDescriptors1 = timeIt(() -> asmReflector.reflectAll(info));
            assertEquals(58, memberDescriptors1.size());
            memberDescriptors1.forEach(md -> {
                log.info("{} : {}", md.getDeclaringClass(), md.getDeclaration());
            });

        }
    }

    @Test
    public void testReflectAll6() throws Exception {
        ASMReflector asmReflector = ASMReflector.getInstance();
        {
            File jar = getRTJar();
            Map<ClassIndex, File> index = asmReflector.getClasses(jar);

            String fqcn = "java.util.jar.JarFile";
            final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn);
            List<MemberDescriptor> memberDescriptors1 = asmReflector.reflectAll(info);
            assertEquals(43, memberDescriptors1.size());
            memberDescriptors1.forEach(md -> {
                log.info("{}", md.getDeclaration());
                // System.out.println(md.declaration);
            });

        }
    }

    @Test
    public void testReflectAll7() throws Exception {
        ASMReflector asmReflector = ASMReflector.getInstance();
        {
            File jar = getJar("guava");
            Map<ClassIndex, File> index = asmReflector.getClasses(jar);

            String fqcn = "com.google.common.base.Joiner";
            final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn);
            List<MemberDescriptor> memberDescriptors1 = asmReflector.reflectAll(info);
            memberDescriptors1.forEach(md -> {
                log.info("{}", md.getDeclaration());
                // System.out.println(md.declaration);
            });

        }
    }

    @Test
    public void testReflectAll8() throws Exception {
        final ASMReflector asmReflector = ASMReflector.getInstance();
        final File file = getTestOutputDir();
        final Map<ClassIndex, File> index = asmReflector.getClasses(file);
        final String fqcn = "meghanada.Gen4";
        final List<MemberDescriptor> memberDescriptors = debugIt(() -> {
            final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn);
            return asmReflector.reflectAll(info);
        });

        memberDescriptors.forEach(md -> {
            log.info("{} : {}", md.getDeclaringClass(), md.getDeclaration());
        });
    }

    @Test
    public void testGetReflectClass1() throws Exception {
        ASMReflector asmReflector = ASMReflector.getInstance();
        {
            String fqcn = "java.util.Map";
            File jar = getRTJar();
            Map<ClassIndex, File> index = asmReflector.getClasses(jar);

            final InheritanceInfo info = timeIt(() -> asmReflector.getReflectInfo(index, fqcn));
        }
    }

    @Test
    public void testGetReflectClass2() throws Exception {
        ASMReflector asmReflector = ASMReflector.getInstance();
        {
            String fqcn = "java.util.stream.Stream";
            File jar = getRTJar();

            Map<ClassIndex, File> index = asmReflector.getClasses(jar);

            final InheritanceInfo info1 = timeIt(() -> asmReflector.getReflectInfo(index, fqcn));
            final InheritanceInfo info2 = timeIt(() -> asmReflector.getReflectInfo(index, fqcn));
        }
    }

    @Test
    public void testReflectInterface1() throws Exception {
        ASMReflector asmReflector = ASMReflector.getInstance();
        {
            String fqcn = "com.google.common.eventbus.SubscriberExceptionHandler";
            File jar = getJar("guava");
            Map<ClassIndex, File> index = asmReflector.getClasses(jar);
            final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn);

            List<MemberDescriptor> memberDescriptors = asmReflector.reflectAll(info);
            memberDescriptors.forEach(m -> {
                log.info("{} : {}", m.getDeclaration(), m.getRawReturnType());
            });
            assertEquals(1, memberDescriptors.size());
        }
    }

    @Test
    public void testReflectAnnotation1() throws Exception {
        ASMReflector asmReflector = ASMReflector.getInstance();
        {
            String fqcn = "org.junit.Test";
            File jar = getJar("junit");
            List<MemberDescriptor> memberDescriptors = timeIt(() -> {
                Map<ClassIndex, File> index = asmReflector.getClasses(jar);
                index.keySet().forEach(classIndex -> {
                    if (classIndex.isAnnotation) {
                        log.info("{}", classIndex);
                    }
                });
                final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn);
                return asmReflector.reflectAll(info);
            });
            memberDescriptors.forEach(m -> {
                log.trace("{}", m.getDeclaration());
            });
            assertEquals(2, memberDescriptors.size());
        }
    }

    @Test
    public void testReflectInnerClass1() throws Exception {
        final ASMReflector asmReflector = ASMReflector.getInstance();
        final File file = getTestOutputDir();
        final Map<ClassIndex, File> index = asmReflector.getClasses(file);

        final String fqcn1 = "meghanada.ManyInnerClass$A";
        final List<MemberDescriptor> memberDescriptors1 = timeIt(() -> {
            final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn1);
            return asmReflector.reflectAll(info);
        });

        memberDescriptors1.forEach(md -> {
            log.info("{} : {} : {}", md.getDeclaringClass(), md.getDeclaration(), md.getReturnType());
        });

        final String fqcn2 = "meghanada.ManyInnerClass$B";
        final List<MemberDescriptor> memberDescriptors2 = timeIt(() -> {
            final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn2);
            return asmReflector.reflectAll(info);
        });

        memberDescriptors2.forEach(md -> {
            log.info("{} : {} : {}", md.getDeclaringClass(), md.getDeclaration(), md.getReturnType());
        });

//        final String fqcn3 = "meghanada.ManyInnerClass$C";
//        final List<MemberDescriptor> memberDescriptors3 = traceIt(() -> {
//            final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn3);
//            return asmReflector.reflectAll(info);
//        });
//
//        memberDescriptors3.forEach(md -> {
//            log.info("{} : {} : {}", md.getDeclaringClass(), md.getDeclaration(), md.getReturnType());
//            log.info("Return {} raw={}", md.getReturnType(), md.returnType);
//            log.info("typeParameters={} map={}", md.typeParameters, md.typeParameterMap);
//        });
    }

    @Test
    public void testReflectInnerClass2() throws Exception {
        final ASMReflector asmReflector = ASMReflector.getInstance();
        final File file = getTestOutputDir();
        final Map<ClassIndex, File> index = asmReflector.getClasses(file);

        final String fqcn1 = "meghanada.HasInnerClass<String, Long>";
        final List<MemberDescriptor> memberDescriptors1 = timeIt(() -> {
            final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn1);
            return asmReflector.reflectAll(info);
        });

        assertEquals(3, memberDescriptors1.size());
        log.info("{}", Strings.repeat("-", 80));
        memberDescriptors1.forEach(md -> {
            log.info("{} : {} : {}", md.getDeclaringClass(), md.getDeclaration(), md.returnType);
        });

        final String fqcn2 = "meghanada.HasInnerClass";
        final List<MemberDescriptor> memberDescriptors2 = timeIt(() -> {
            final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn2);
            return asmReflector.reflectAll(info);
        });

        assertEquals(3, memberDescriptors2.size());
        log.info("{} : {}", Strings.repeat("-", 80));
        memberDescriptors2.forEach(md -> {
            log.info("{} : {} : {}", md.getDeclaringClass(), md.getDeclaration(), md.returnType);
        });
    }

    private List<MemberDescriptor> refrect(String fqcn) throws IOException {
        final ASMReflector asmReflector = ASMReflector.getInstance();
        final File file = getTestOutputDir();
        final Map<ClassIndex, File> index = asmReflector.getClasses(file);
        final List<MemberDescriptor> memberDescriptors = traceIt(() -> {
            final InheritanceInfo info = asmReflector.getReflectInfo(index, fqcn);
            return asmReflector.reflectAll(info);
        });

        memberDescriptors.forEach(md -> {
            System.out.println(md.getDeclaringClass() + " # " + md.getDeclaration() + " # " + md.returnType);
        });
        return memberDescriptors;
    }
}
