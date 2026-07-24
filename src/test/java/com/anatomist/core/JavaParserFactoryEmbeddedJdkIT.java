package com.anatomist.core;

import com.anatomist.core.nativeimage.EmbeddedJdkTypeSolver;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Verify JavaParserFactory wiring picks the right JDK solver — either the
 *  shipped EmbeddedJdkTypeSolver catalog when present, or ReflectionTypeSolver
 *  otherwise. */
class JavaParserFactoryEmbeddedJdkIT {

    @Test
    void outsideNativeImage_alwaysUsesReflectionTypeSolver() throws Exception {
        // Outside native-image, ReflectionTypeSolver is always used because
        // EmbeddedJdkTypeSolver has incomplete type declarations that break
        // method resolution in CombinedTypeSolver.
        JavaParserFactory f = new JavaParserFactory(
                21, List.of(), List.<Path>of(), /*includeRunningVmClasspath=*/true);
        CombinedTypeSolver ts = f.newTypeSolver();
        List<TypeSolver> children = innerSolvers(ts);
        boolean hasEmbedded = children.stream().anyMatch(s -> s instanceof EmbeddedJdkTypeSolver);
        boolean hasReflection = children.stream().anyMatch(s -> s instanceof ReflectionTypeSolver);
        assertFalse(hasEmbedded,
                "outside native-image -> EmbeddedJdkTypeSolver must NOT be in chain");
        assertTrue(hasReflection,
                "outside native-image -> ReflectionTypeSolver required; got " + children);
    }

    @Test
    void includeRunningVmClasspathFalse_addsNoJdkSolver() throws Exception {
        JavaParserFactory f = new JavaParserFactory(
                21, List.of(), List.<Path>of(), /*includeRunningVmClasspath=*/false);
        CombinedTypeSolver ts = f.newTypeSolver();
        List<TypeSolver> children = innerSolvers(ts);
        assertTrue(children.stream().noneMatch(s ->
                s instanceof EmbeddedJdkTypeSolver || s instanceof ReflectionTypeSolver),
                "JDK solvers must be absent when includeRunningVmClasspath=false; got " + children);
    }

    @Test
    void nativeImage_usesConfiguredLocalJdkCatalog(@TempDir Path tmp) throws Exception {
        String imageCode = System.getProperty("org.graalvm.nativeimage.imagecode");
        String userHome = System.getProperty("user.home");
        Path jdkHome = Path.of(System.getProperty("java.home"));
        int release = com.anatomist.core.nativeimage.JdkTypeCatalogBuilder.releaseOf(jdkHome);
        try {
            System.setProperty("org.graalvm.nativeimage.imagecode", "runtime");
            System.setProperty("user.home", tmp.toString());
            JavaParserFactory factory = new JavaParserFactory(release, List.of(), List.of(), true, jdkHome);
            assertTrue(innerSolvers(factory.newTypeSolver()).stream()
                    .anyMatch(s -> s instanceof EmbeddedJdkTypeSolver));
        } finally {
            restoreProperty("org.graalvm.nativeimage.imagecode", imageCode);
            restoreProperty("user.home", userHome);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    @SuppressWarnings("unchecked")
    private static List<TypeSolver> innerSolvers(CombinedTypeSolver ts) throws Exception {
        Field f = CombinedTypeSolver.class.getDeclaredField("elements");
        f.setAccessible(true);
        return (List<TypeSolver>) f.get(ts);
    }

    private static boolean catalogResourceExists() {
        for (int v = 25; v >= 8; v--) {
            if (JavaParserFactoryEmbeddedJdkIT.class.getResource(
                    "/META-INF/anatomist/jdk" + v + "-types.bin") != null) {
                return true;
            }
        }
        return false;
    }
}
