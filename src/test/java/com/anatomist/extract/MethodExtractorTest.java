package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.JavaParserTestSupport;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MethodExtractorTest {

    private final ExtractionContext ctx = new ExtractionContext(
            Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN");

    @Test
    void extract_emitsMethodNodeAndContainsEdge() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; public class A { public void foo() {} }");
        ExtractionResult r = new ExtractionResult();
        new MethodExtractor(ctx).extract(cu, r);

        assertEquals(1, r.nodes.size());
        Node n = r.nodes.get(0);
        assertEquals("pkg.A#foo()", n.id);
        assertEquals("METHOD", n.kind);

        assertEquals(1, r.edges.size());
        Edge e = r.edges.get(0);
        assertEquals("pkg.A", e.sourceId);
        assertEquals("pkg.A#foo()", e.targetId);
        assertEquals("CONTAINS", e.relation);
        assertFalse(e.isExternal);
        assertNull(e.externalTargetFqn);
    }

    @Test
    void extract_distinguishesOverloads() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; public class A {"
                + "  public void foo() {}"
                + "  public void foo(String s) {}"
                + "}");
        ExtractionResult r = new ExtractionResult();
        new MethodExtractor(ctx).extract(cu, r);

        Set<String> ids = r.nodes.stream().map(n -> n.id).collect(Collectors.toSet());
        assertTrue(ids.contains("pkg.A#foo()"));
        assertTrue(ids.contains("pkg.A#foo(java.lang.String)"));
    }

    @Test
    void extract_handlesConstructorAndGenericList() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; import java.util.List;"
                + "public class A {"
                + "  public A() {}"
                + "  public void bar(List<Integer> xs) {}"
                + "}");
        ExtractionResult r = new ExtractionResult();
        new MethodExtractor(ctx).extract(cu, r);

        Set<String> ids = r.nodes.stream().map(n -> n.id).collect(Collectors.toSet());
        assertTrue(ids.contains("pkg.A#A()"), "constructor id; got " + ids);
        assertTrue(ids.contains("pkg.A#bar(java.util.List)"), "erased generic; got " + ids);

        Node ctor = r.nodes.stream().filter(n -> n.id.equals("pkg.A#A()")).findFirst().orElseThrow();
        assertTrue(ctor.metadata.contains("\"isConstructor\":true"));
    }
}
