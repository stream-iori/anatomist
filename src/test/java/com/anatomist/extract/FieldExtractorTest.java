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

class FieldExtractorTest {

    private final ExtractionContext ctx = new ExtractionContext(
            Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN");

    @Test
    void emitsFieldNodeAndContainsEdge() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; public class A { private String name; }");
        ExtractionResult r = new ExtractionResult();
        new FieldExtractor(ctx).extract(cu, r);

        assertEquals(1, r.nodes.size());
        Node n = r.nodes.get(0);
        assertEquals("pkg.A#name", n.id);
        assertEquals("FIELD", n.kind);
        assertTrue(n.metadata.contains("\"type\":\"java.lang.String\""), n.metadata);

        assertEquals(1, r.edges.size());
        Edge e = r.edges.get(0);
        assertEquals("pkg.A", e.sourceId);
        assertEquals("pkg.A#name", e.targetId);
        assertEquals("CONTAINS", e.relation);
    }

    @Test
    void emitsMultiVariableField() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; public class A { int a, b, c; }");
        ExtractionResult r = new ExtractionResult();
        new FieldExtractor(ctx).extract(cu, r);

        Set<String> ids = r.nodes.stream().map(n -> n.id).collect(Collectors.toSet());
        assertEquals(Set.of("pkg.A#a", "pkg.A#b", "pkg.A#c"), ids);
    }

    @Test
    void emitsEnumConstant() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; public enum E { ALPHA, BETA }");
        ExtractionResult r = new ExtractionResult();
        new FieldExtractor(ctx).extract(cu, r);

        Set<String> ids = r.nodes.stream().map(n -> n.id).collect(Collectors.toSet());
        assertTrue(ids.contains("pkg.E#ALPHA"), "got " + ids);
        assertTrue(ids.contains("pkg.E#BETA"));
        long enumKindCount = r.nodes.stream()
                .filter(n -> "ENUM_CONSTANT".equals(n.kind)).count();
        assertEquals(2, enumKindCount);
    }

    @Test
    void extract_recordComponents_emitsFieldNodes() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; public record Point(int x, int y) {}");
        ExtractionResult r = new ExtractionResult();
        new FieldExtractor(ctx).extract(cu, r);

        Set<String> ids = r.nodes.stream()
                .filter(n -> "FIELD".equals(n.kind))
                .map(n -> n.id).collect(Collectors.toSet());
        assertTrue(ids.contains("pkg.Point#x"), "got " + ids);
        assertTrue(ids.contains("pkg.Point#y"), "got " + ids);
        long containsX = r.edges.stream().filter(e ->
                "CONTAINS".equals(e.relation)
                        && "pkg.Point".equals(e.sourceId)
                        && "pkg.Point#x".equals(e.targetId)).count();
        assertEquals(1, containsX, "got " + r.edges);
        Set<String> methods = r.nodes.stream()
                .filter(n -> "METHOD".equals(n.kind))
                .map(n -> n.id).collect(Collectors.toSet());
        assertTrue(methods.contains("pkg.Point#x()"), methods.toString());
        assertTrue(methods.contains("pkg.Point#y()"), methods.toString());
        assertTrue(methods.contains("pkg.Point#Point(int,int)"), methods.toString());
    }

    @Test
    void explicitRecordAccessor_isNotSynthesizedTwice() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; public record Name(String value) {"
                + " public String value() { return value.trim(); }"
                + "}");
        ExtractionResult r = new ExtractionResult();
        new FieldExtractor(ctx).extract(cu, r);

        assertEquals(0, r.nodes.stream()
                .filter(n -> "pkg.Name#value()".equals(n.id)).count());
    }
}
