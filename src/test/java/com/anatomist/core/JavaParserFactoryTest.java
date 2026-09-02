package com.anatomist.core;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaParserFactoryTest {

    @Test
    void cacheBudgetsCanBeTunedAndInvalidValuesFallBack() {
        String oldSource = System.getProperty(JavaParserFactory.SOURCE_CACHE_PROPERTY);
        String oldCombined = System.getProperty(JavaParserFactory.COMBINED_CACHE_PROPERTY);
        try {
            System.setProperty(JavaParserFactory.SOURCE_CACHE_PROPERTY, "64");
            System.setProperty(JavaParserFactory.COMBINED_CACHE_PROPERTY, "10000");
            assertEquals(64, JavaParserFactory.fullSourceCacheSize());
            assertEquals(10_000, JavaParserFactory.combinedTypeCacheSize());

            System.setProperty(JavaParserFactory.SOURCE_CACHE_PROPERTY, "0");
            System.setProperty(JavaParserFactory.COMBINED_CACHE_PROPERTY, "bad");
            assertEquals(256, JavaParserFactory.fullSourceCacheSize());
            assertEquals(20_000, JavaParserFactory.combinedTypeCacheSize());
        } finally {
            restore(JavaParserFactory.SOURCE_CACHE_PROPERTY, oldSource);
            restore(JavaParserFactory.COMBINED_CACHE_PROPERTY, oldCombined);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }

    @Test
    void parseAll_parsesEverySourceFile(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Files.writeString(src.resolve("A.java"), "package p; public class A {}");
        Files.writeString(src.resolve("B.java"), "package p; public class B {}");

        JavaParserFactory factory = new JavaParserFactory(
                21, List.of(), List.of(src), /*vmClasspath*/ true);

        List<String> seen = new ArrayList<>();
        factory.parseAll((path, cu) -> {
            cu.getTypes().forEach(t -> seen.add(t.getNameAsString()));
        });
        seen.sort(String::compareTo);
        assertEquals(List.of("A", "B"), seen);
    }

    @Test
    void parseFiles_attachesSymbolResolver(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path a = src.resolve("A.java");
        Files.writeString(a, "package p; public class A { public void foo() {} }");

        JavaParserFactory factory = new JavaParserFactory(
                21, List.of(), List.of(src), /*vmClasspath*/ true);

        List<CompilationUnit> cus = factory.parseFiles(List.of(a));
        assertEquals(1, cus.size());
        // resolve() should not throw with the SymbolResolver attached
        cus.get(0).findAll(com.github.javaparser.ast.body.MethodDeclaration.class)
                .forEach(m -> assertDoesNotThrow(m::resolve));
    }

    @Test
    void sourceTypeSolverAstUsesTheCombinedSymbolResolver(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src/p"));
        Path dep = src.resolve("Dep.java");
        Files.writeString(dep, """
                package p;
                import java.util.Optional;
                class Dep { Optional<String> value() { return Optional.empty(); } }
                """);
        JavaParserFactory factory = new JavaParserFactory(
                25, List.of(), List.of(tmp.resolve("src")), true);

        var declaration = factory.newTypeSolver().solveType("p.Dep");
        var ast = ((com.github.javaparser.symbolsolver.javaparsermodel.declarations
                .JavaParserClassDeclaration) declaration).getWrappedNode();
        MethodCallExpr empty = ast.findFirst(MethodCallExpr.class).orElseThrow();

        assertEquals("java.util.Optional", empty.resolve().declaringType().getQualifiedName());
    }

    @Test
    void classpathDirectoryIsUsedByParser(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes/com/dep"));
        Files.write(classes.resolve("External.class"),
                miniClass("com.dep.External"));
        Path src = Files.createDirectories(tmp.resolve("src/p"));
        Path source = src.resolve("Use.java");
        Files.writeString(source, "package p; class Use { com.dep.External value; }");
        Path sourceRoot = tmp.resolve("src");

        JavaParserFactory regular = new JavaParserFactory(
                21, List.of(tmp.resolve("classes")), List.of(sourceRoot), true);
        IndexTimings timings = new IndexTimings();
        regular.setTimings(timings);
        assertEquals("com.dep.External", resolvedFieldType(regular.parseFiles(List.of(source)).get(0)));
        assertTrue(timings.millis().containsKey("classpath_index_build"));
        assertTrue(timings.millis().containsKey("type_cache_load"));
    }

    private static String resolvedFieldType(CompilationUnit unit) {
        return unit.findFirst(com.github.javaparser.ast.body.FieldDeclaration.class)
                .orElseThrow().getVariable(0).resolve().getType()
                .asReferenceType().getQualifiedName();
    }

    private static byte[] miniClass(String fqn) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                fqn.replace('.', '/'), null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void parseFilesDetailedPreservesProblemsForInvalidSource(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path invalid = src.resolve("Broken.java");
        Files.writeString(invalid, "package p; class Broken { void run() {} throws Exception }");

        JavaParserFactory factory = new JavaParserFactory(
                21, List.of(), List.of(src), /*vmClasspath*/ true);

        JavaParserFactory.ParseFilesResult result = factory.parseFilesDetailed(List.of(invalid));

        assertTrue(result.compilationUnits().isEmpty());
        assertTrue(result.problems().containsKey(invalid.toAbsolutePath().normalize()));
        assertFalse(result.problems().get(invalid.toAbsolutePath().normalize()).isEmpty());
    }

    @Test
    void parseInventoryAccountsForEveryScannedFile(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path valid = src.resolve("Valid.java");
        Path invalid = src.resolve("Broken.java");
        Files.writeString(valid, "class Valid {}");
        Files.writeString(invalid, "class Broken {");
        JavaParserFactory factory = new JavaParserFactory(
                17, List.of(), List.of(src), true);
        List<Path> parsed = new ArrayList<>();

        ParseInventory inventory =
                factory.parseInventory(List.of(valid, invalid), (file, unit) -> parsed.add(file));

        assertEquals(2, inventory.scannedFiles());
        assertEquals(2, inventory.attemptedFiles());
        assertEquals(1, inventory.parsedFiles());
        assertEquals(1, inventory.failedFiles());
        assertFalse(inventory.complete());
        assertEquals(List.of(valid.toAbsolutePath().normalize()), parsed);
    }

    @Test
    void toLanguageLevel_supportsJava17() {
        assertEquals(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17,
                JavaParserFactory.toLanguageLevel(17));
    }

    @Test
    void toLanguageLevel_supportsJava25() {
        assertEquals(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_25,
                JavaParserFactory.toLanguageLevel(25));
    }

    @Test
    void toLanguageLevel_rejectsVersionsOutsideSupportedRange() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> JavaParserFactory.toLanguageLevel(26));
        assertTrue(error.getMessage().contains("8..25"));
    }

    @Test
    void toLanguageLevel_supportsJava16ForRecords() {
        assertEquals(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_16,
                JavaParserFactory.toLanguageLevel(16));
    }
}
