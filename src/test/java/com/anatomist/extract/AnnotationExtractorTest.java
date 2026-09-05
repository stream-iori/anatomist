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

    @Test
    void preservesUnresolvedAnnotationAndStructuralTarget() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; class A { @Missing String value; }");
        ExtractionResult r = new ExtractionResult();
        new AnnotationExtractor(ctx).extract(cu, r);

        Annotation annotation = r.annotations.stream()
                .filter(value -> "Missing".equals(value.rawName)).findFirst().orElseThrow();
        assertNull(annotation.annotationFqn);
        assertEquals("unresolved", annotation.resolutionStatus);
        assertEquals("value", annotation.targetKind);
        assertNotNull(annotation.beginLine);
        assertNotNull(annotation.endColumn);
    }

    @Test
    void coversRecordComponentsAndEnumConstants() {
        CompilationUnit cu = JavaParserTestSupport.parse("""
                package pkg;
                @interface Mark {}
                record R(@Mark String name) {}
                enum E { @Mark ONE }
                """);
        ExtractionResult r = new ExtractionResult();
        new AnnotationExtractor(ctx).extract(cu, r);

        Annotation component = r.annotations.stream()
                .filter(value -> "component[0]".equals(value.targetPath)).findFirst().orElseThrow();
        assertEquals("pkg.R#name", component.nodeId);
        assertEquals("value", component.targetKind);
        Annotation constant = r.annotations.stream()
                .filter(value -> "pkg.E#ONE".equals(value.nodeId)).findFirst().orElseThrow();
        assertEquals("value", constant.targetKind);
    }

    @Test
    void recordsDirectMetaRelations() {
        CompilationUnit cu = JavaParserTestSupport.parse("""
                package pkg;
                @Deprecated @interface Composed {}
                @Composed class A {}
                """);
        ExtractionResult r = new ExtractionResult();
        new AnnotationExtractor(ctx).extract(cu, r);

        assertTrue(r.annotationMetaRelations.stream().anyMatch(value ->
                "pkg.Composed".equals(value.annotationFqn)
                        && "java.lang.Deprecated".equals(value.metaAnnotationFqn)));
    }
}
