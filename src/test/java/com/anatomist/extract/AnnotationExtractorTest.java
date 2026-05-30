package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.JavaParserTestSupport;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.model.Annotation;
import com.anatomist.model.ExtractionResult;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AnnotationExtractorTest {

    private final ExtractionContext ctx = new ExtractionContext(
            Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN");

    @Test
    void resolvesJdkAnnotationOnMethod() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A extends Object {\n"
                + "  @Override public String toString() { return \"a\"; }\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new AnnotationExtractor(ctx).extract(cu, r);

        Optional<Annotation> override = r.annotations.stream()
                .filter(a -> "java.lang.Override".equals(a.annotationFqn))
                .findFirst();
        assertTrue(override.isPresent(), "expected @Override resolved; got " + r.annotations);
        assertTrue(override.get().nodeId.startsWith("pkg.A#toString("));
    }

    @Test
    void capturesAnnotationAttributes() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A {\n"
                + "  @SuppressWarnings(\"unchecked\") void m() {}\n"
                + "  @SuppressWarnings({\"a\", \"b\"}) void n() {}\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new AnnotationExtractor(ctx).extract(cu, r);

        Annotation single = r.annotations.stream()
                .filter(a -> a.nodeId.startsWith("pkg.A#m("))
                .findFirst().orElseThrow();
        assertEquals("java.lang.SuppressWarnings", single.annotationFqn);
        assertTrue(single.attributes.contains("\"value\":\"unchecked\""), single.attributes);

        Annotation array = r.annotations.stream()
                .filter(a -> a.nodeId.startsWith("pkg.A#n("))
                .findFirst().orElseThrow();
        assertTrue(array.attributes.contains("[\"a\",\"b\"]"), array.attributes);
    }

    @Test
    void parameterAnnotationCarriesIndexAndName() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A {\n"
                + "  void m(@Deprecated String s) {}\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new AnnotationExtractor(ctx).extract(cu, r);

        Annotation a = r.annotations.stream()
                .filter(x -> "java.lang.Deprecated".equals(x.annotationFqn))
                .findFirst().orElseThrow();
        assertTrue(a.attributes.contains("\"_param\":0"), a.attributes);
        assertTrue(a.attributes.contains("\"_name\":\"s\""), a.attributes);
    }
}
