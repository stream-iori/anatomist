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

class HierarchyExtractorTest {

    private final ExtractionContext ctx = new ExtractionContext(
            Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN");

    @Test
    void extract_emitsInheritsAndImplements() {
        CompilationUnit cu = JdtTestSupport.parse("pkg/All.java",
                "package pkg;" +
                        "interface I {} interface J {} class P {} class C extends P implements I,J {}");
        ExtractionResult r = new ExtractionResult();
        new HierarchyExtractor(ctx).extract(cu, r);

        long inherits = r.edges.stream().filter(e -> "INHERITS".equals(e.relation)).count();
        long implementsCount = r.edges.stream().filter(e -> "IMPLEMENTS".equals(e.relation)).count();
        assertEquals(1, inherits, "got " + r.edges);
        assertEquals(2, implementsCount, "got " + r.edges);
    }

    @Test
    void extract_emitsExternalSuperclass() {
        CompilationUnit cu = JdtTestSupport.parse("pkg/C.java",
                "package pkg; public class C extends java.util.ArrayList<String> {}");
        ExtractionResult r = new ExtractionResult();
        new HierarchyExtractor(ctx).extract(cu, r);

        Edge ext = r.edges.stream()
                .filter(e -> "INHERITS".equals(e.relation) && e.isExternal)
                .findFirst().orElseThrow();
        assertEquals("java.util.ArrayList", ext.externalTargetFqn);
        assertNull(ext.targetId);
    }

    @Test
    void extract_emitsOverrides() {
        CompilationUnit cu = JdtTestSupport.parse("pkg/All.java",
                "package pkg; class P { public void f(String s){} } " +
                        "class C extends P { @Override public void f(String s){} }");
        ExtractionResult r = new ExtractionResult();
        new HierarchyExtractor(ctx).extract(cu, r);

        Edge ov = r.edges.stream().filter(e -> "OVERRIDES".equals(e.relation))
                .findFirst().orElseThrow();
        assertEquals("pkg.C#f(java.lang.String)", ov.sourceId);
        assertEquals("pkg.P#f(java.lang.String)", ov.targetId);
    }

    @Test
    void extract_distinguishesOverloadedOverride() {
        CompilationUnit cu = JdtTestSupport.parse("pkg/All.java",
                "package pkg; class P { public void f(String s){} public void f(int i){} } " +
                        "class C extends P { @Override public void f(String s){} }");
        ExtractionResult r = new ExtractionResult();
        new HierarchyExtractor(ctx).extract(cu, r);

        long overrideCount = r.edges.stream().filter(e -> "OVERRIDES".equals(e.relation)).count();
        assertEquals(1, overrideCount, "should match only the String overload; got " + r.edges);
    }
}
