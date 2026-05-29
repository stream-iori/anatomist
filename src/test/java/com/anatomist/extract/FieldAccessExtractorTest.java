package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.JdtTestSupport;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FieldAccessExtractorTest {

    private final ExtractionContext ctx = new ExtractionContext(
            Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN");

    @Test
    void extract_emitsWritesForAssignmentLhs() {
        CompilationUnit cu = JdtTestSupport.parse("pkg/A.java",
                "package pkg; class A { int n; void f(){ n = 1; } }");
        ExtractionResult r = new ExtractionResult();
        new FieldAccessExtractor(ctx).extract(cu, r);

        long writes = r.edges.stream()
                .filter(e -> "WRITES".equals(e.relation) && "pkg.A#n".equals(e.targetId))
                .count();
        assertEquals(1, writes, "got " + r.edges);
    }

    @Test
    void extract_emitsReadsForRhs() {
        CompilationUnit cu = JdtTestSupport.parse("pkg/A.java",
                "package pkg; class A { int n; int g(){ return n; } }");
        ExtractionResult r = new ExtractionResult();
        new FieldAccessExtractor(ctx).extract(cu, r);

        long reads = r.edges.stream()
                .filter(e -> "READS".equals(e.relation) && "pkg.A#n".equals(e.targetId))
                .count();
        assertEquals(1, reads, "got " + r.edges);
    }

    @Test
    void extract_emitsBothForCompoundAssignment() {
        CompilationUnit cu = JdtTestSupport.parse("pkg/A.java",
                "package pkg; class A { int n; void inc(){ n += 1; } }");
        ExtractionResult r = new ExtractionResult();
        new FieldAccessExtractor(ctx).extract(cu, r);

        long writes = r.edges.stream()
                .filter(e -> "WRITES".equals(e.relation) && "pkg.A#n".equals(e.targetId)).count();
        long reads = r.edges.stream()
                .filter(e -> "READS".equals(e.relation) && "pkg.A#n".equals(e.targetId)).count();
        assertEquals(1, writes, "compound assignment should emit WRITES; got " + r.edges);
        assertEquals(1, reads, "compound assignment should emit READS; got " + r.edges);
    }

    @Test
    void extract_handlesIncrementDecrement() {
        CompilationUnit cu = JdtTestSupport.parse("pkg/A.java",
                "package pkg; class A { int n; void inc(){ n++; ++n; n--; } }");
        ExtractionResult r = new ExtractionResult();
        new FieldAccessExtractor(ctx).extract(cu, r);

        long writes = r.edges.stream()
                .filter(e -> "WRITES".equals(e.relation) && "pkg.A#n".equals(e.targetId)).count();
        assertEquals(3, writes, "got " + r.edges);
    }

    @Test
    void extract_skipsLocalVariablesAndExternalFields() {
        CompilationUnit cu = JdtTestSupport.parse("pkg/A.java",
                "package pkg; class A { void f(){ int x = 0; x = 1; System.out.println(x); } }");
        ExtractionResult r = new ExtractionResult();
        new FieldAccessExtractor(ctx).extract(cu, r);

        assertTrue(r.edges.isEmpty(),
                "local vars and external (System.out) fields should not produce edges; got " + r.edges);
    }
}
