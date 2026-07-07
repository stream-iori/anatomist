package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.JavaParserTestSupport;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TypeExtractorTest {

    private final ExtractionContext ctx = new ExtractionContext(
            Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN");

    @Test
    void extract_emitsClassNode() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; public class Order {}");
        ExtractionResult result = new ExtractionResult();

        new TypeExtractor(ctx).extract(cu, result);

        assertEquals(1, result.nodes.size());
        Node n = result.nodes.get(0);
        assertEquals("pkg.Order", n.id);
        assertEquals("CLASS", n.kind);
        assertEquals("Order", n.label);
        assertEquals("pkg", n.pkg);
        assertTrue(n.metadata.contains("\"isAbstract\":false"));
        assertTrue(n.metadata.contains("\"isInterface\":false"));
    }

    @Test
    void extract_emitsInterfaceAndEnum() {
        CompilationUnit ifCu = JavaParserTestSupport.parse(
                "package pkg; public interface I {}");
        CompilationUnit enumCu = JavaParserTestSupport.parse(
                "package pkg; public enum E { A, B }");
        ExtractionResult result = new ExtractionResult();
        TypeExtractor te = new TypeExtractor(ctx);
        te.extract(ifCu, result);
        te.extract(enumCu, result);

        Node i = byId(result, "pkg.I");
        Node e = byId(result, "pkg.E");
        assertEquals("INTERFACE", i.kind);
        assertEquals("ENUM", e.kind);
        assertTrue(e.metadata.contains("\"constants\":[\"A\",\"B\"]"), "got: " + e.metadata);
    }

    @Test
    void extract_emitsNestedTypes() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; public class A { public static class B {} }");
        ExtractionResult result = new ExtractionResult();
        new TypeExtractor(ctx).extract(cu, result);

        assertEquals(2, result.nodes.size());
        Optional<Node> outer = result.nodes.stream().filter(n -> n.id.equals("pkg.A")).findFirst();
        Optional<Node> inner = result.nodes.stream().filter(n -> n.id.equals("pkg.A.B")).findFirst();
        assertTrue(outer.isPresent());
        assertTrue(inner.isPresent());
    }

    @Test
    void visit_recordDeclaration_emitsRecordNode() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; public record Point(int x, int y) {}");
        ExtractionResult r = new ExtractionResult();
        new TypeExtractor(ctx).extract(cu, r);

        Node rec = r.nodes.stream().filter(n -> "RECORD".equals(n.kind)).findFirst()
                .orElseThrow(() -> new AssertionError("no RECORD; got " + r.nodes));
        assertEquals("pkg.Point", rec.id);
    }

    @Test
    void visit_anonymousClassInsideUnresolvedAnonymousMethod_doesNotAbort() {
        CompilationUnit cu = JavaParserTestSupport.parse("""
                package pkg;
                public class A {
                  void outer() {
                    new MissingCallback() {
                      public void done() {
                        new TypeReference<String>() {};
                      }
                    };
                  }
                }
                """);
        ExtractionResult r = new ExtractionResult();

        assertDoesNotThrow(() -> new TypeExtractor(ctx).extract(cu, r));

        assertTrue(r.nodes.stream().anyMatch(n ->
                        "ANONYMOUS_CLASS".equals(n.kind)
                                && n.id.startsWith("pkg.A#outer()$anon@L")
                                && n.id.contains("#done()$anon@L")),
                "nested anonymous class should use fallback method owner; got " + r.nodes);
    }

    private static Node byId(ExtractionResult r, String id) {
        return r.nodes.stream().filter(n -> n.id.equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no node with id " + id + "; got " + r.nodes));
    }
}
