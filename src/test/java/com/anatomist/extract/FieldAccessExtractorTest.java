package com.anatomist.extract;

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
}
