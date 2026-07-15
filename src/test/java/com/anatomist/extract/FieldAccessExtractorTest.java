package com.anatomist.extract;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.ExtractionContext;
import com.anatomist.core.JavaParserTestSupport;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.model.ExtractionResult;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class FieldAccessExtractorTest {

    private final ExtractionContext ctx = new ExtractionContext(
            Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN");

    @Test
    void writePlainAssignment() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A {\n"
                + "  int x;\n"
                + "  void set() { this.x = 5; }\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new FieldAccessExtractor(ctx).extract(cu, r);

        long writes = r.edges.stream().filter(e ->
                "WRITES".equals(e.relation)
                        && "pkg.A#set()".equals(e.sourceId)
                        && "pkg.A#x".equals(e.targetId)).count();
        assertEquals(1, writes, "got " + r.edges);
        long reads = r.edges.stream().filter(e -> "READS".equals(e.relation)).count();
        assertEquals(0, reads, "plain `=` shouldn't emit READS; got " + r.edges);
    }

    @Test
    void readViaNameExpr() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A {\n"
                + "  int x;\n"
                + "  int get() { return x + 1; }\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new FieldAccessExtractor(ctx).extract(cu, r);

        long reads = r.edges.stream().filter(e ->
                "READS".equals(e.relation)
                        && "pkg.A#get()".equals(e.sourceId)
                        && "pkg.A#x".equals(e.targetId)).count();
        assertEquals(1, reads, "got " + r.edges);
    }

    @Test
    void compoundAssignmentEmitsBoth() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A {\n"
                + "  int x;\n"
                + "  void inc() { x += 1; }\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new FieldAccessExtractor(ctx).extract(cu, r);

        List<String> kinds = r.edges.stream()
                .filter(e -> "pkg.A#x".equals(e.targetId))
                .map(e -> e.relation)
                .collect(Collectors.toList());
        assertTrue(kinds.contains("WRITES"), "got " + r.edges);
        assertTrue(kinds.contains("READS"), "got " + r.edges);
    }

    @Test
    void incrementEmitsWrite() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A {\n"
                + "  int x;\n"
                + "  void bump() { x++; }\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new FieldAccessExtractor(ctx).extract(cu, r);

        long writes = r.edges.stream().filter(e ->
                "WRITES".equals(e.relation) && "pkg.A#x".equals(e.targetId)).count();
        assertEquals(1, writes, "got " + r.edges);
        long reads = r.edges.stream().filter(e ->
                "READS".equals(e.relation) && "pkg.A#x".equals(e.targetId)).count();
        assertEquals(0, reads, "x++ shouldn't emit READS; got " + r.edges);
    }

    @Test
    void enclosingId_lambdaBodyFieldAccess_attributesToLambdaNode() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; import java.util.function.Supplier;"
                + "public class A {"
                + "  int x;"
                + "  void run() {"
                + "    Supplier<Integer> sup = () -> x + 1;"
                + "  }"
                + "}");
        ExtractionResult r = new ExtractionResult();
        new FieldAccessExtractor(ctx).extract(cu, r);

        var read = r.edges.stream()
                .filter(e -> "READS".equals(e.relation) && "pkg.A#x".equals(e.targetId))
                .findFirst().orElseThrow(() -> new AssertionError("no READS; got " + r.edges));
        assertTrue(read.sourceId.startsWith("pkg.A#run()$lambda@L"),
                "field access inside lambda must attribute to LAMBDA node; got " + read.sourceId);
    }

    /**
     * Regression for the structural-equality bug in the write-site set:
     * a vanilla {@code HashSet<Node>} would mark BOTH sides of {@code x = x + 1}
     * as write sites (because two NameExprs spelling "x" are
     * {@link com.github.javaparser.ast.Node#equals(Object) Node.equals}-equal),
     * silently dropping the RHS READ and — worse — every other READ of
     * {@code x} elsewhere in the file. Identity-only membership fixes it.
     */
    @Test
    void selfAssignment_rhsEmitsRead_andOtherReadsInSameClassSurvive() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A {\n"
                + "  int x;\n"
                + "  void inc()   { x = x + 1; }\n"
                + "  int  value() { return x; }\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new FieldAccessExtractor(ctx).extract(cu, r);

        long writes = r.edges.stream().filter(e ->
                "WRITES".equals(e.relation) && "pkg.A#x".equals(e.targetId)).count();
        long reads  = r.edges.stream().filter(e ->
                "READS".equals(e.relation) && "pkg.A#x".equals(e.targetId)).count();
        assertEquals(1, writes, "got " + r.edges);
        assertEquals(2, reads, "expected RHS read + return read; got " + r.edges);
    }

    @Test
    void nestedFieldTargetWritesLeafAndReadsReceiverField() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A {\n"
                + "  B holder;\n"
                + "  void set() { this.holder.value = 1; }\n"
                + "  static class B { int value; }\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new FieldAccessExtractor(ctx).extract(cu, r);

        assertEquals(1, r.edges.stream().filter(e -> "WRITES".equals(e.relation)
                && "pkg.A.B#value".equals(e.targetId)).count(), "got " + r.edges);
        assertEquals(1, r.edges.stream().filter(e -> "READS".equals(e.relation)
                && "pkg.A#holder".equals(e.targetId)).count(), "got " + r.edges);
    }

    @Test
    void context_recordedForWriteInsideCatch() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A {\n"
                + "  boolean failed;\n"
                + "  void run(){\n"
                + "    try { risky(); }\n"
                + "    catch (RuntimeException e) { this.failed = true; }\n"
                + "  }\n"
                + "  void risky(){}\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new FieldAccessExtractor(ctx).extract(cu, r);

        var write = r.edges.stream()
                .filter(e -> "WRITES".equals(e.relation) && "pkg.A#failed".equals(e.targetId))
                .findFirst().orElseThrow(() -> new AssertionError("got " + r.edges));
        assertEquals("catch@L6", write.context);
    }

    @Test
    void context_nullForUnconditionalRead() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A {\n"
                + "  int x;\n"
                + "  int get() { return x; }\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new FieldAccessExtractor(ctx).extract(cu, r);

        var read = r.edges.stream()
                .filter(e -> "READS".equals(e.relation) && "pkg.A#x".equals(e.targetId))
                .findFirst().orElseThrow();
        assertNull(read.context);
    }

    @Test
    void emitsExternalReadForSystemOut() {
        // Allow java.lang.System through by using a config that only excludes java.io.*
        ProjectConfig config = new ProjectConfig();
        config.setExternalExcludePatterns(List.of("java.io.*"));
        ExtractionContext ctxWithConfig = new ExtractionContext(
                Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN", config);

        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A {\n"
                + "  void run() { System.out.println(\"hi\"); }\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new FieldAccessExtractor(ctxWithConfig).extract(cu, r);

        boolean externalRead = r.edges.stream().anyMatch(e ->
                "READS".equals(e.relation)
                        && e.isExternal
                        && "java.lang.System#out".equals(e.externalTargetFqn));
        assertTrue(externalRead,
                "external READS for System.out expected; got " + r.edges);
    }

    @Test
    void externalFieldAccessExcludedByConfig() {
        // Exclude everything under java.*
        ProjectConfig config = new ProjectConfig();
        config.setExternalExcludePatterns(List.of("java.**"));
        ExtractionContext ctxExcludeAll = new ExtractionContext(
                Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN", config);

        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A {\n"
                + "  void run() { System.out.println(\"hi\"); }\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new FieldAccessExtractor(ctxExcludeAll).extract(cu, r);

        boolean anyExternal = r.edges.stream().anyMatch(e -> e.isExternal);
        assertFalse(anyExternal,
                "java.** should be excluded; got " + r.edges);
    }
}
