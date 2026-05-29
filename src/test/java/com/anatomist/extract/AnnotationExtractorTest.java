package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.JdtTestSupport;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.model.Annotation;
import com.anatomist.model.ExtractionResult;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AnnotationExtractorTest {

    private final ExtractionContext ctx = new ExtractionContext(
            Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN");

    @Test
    void extract_collectsClassMethodFieldParameterAnnotations() {
        CompilationUnit cu = JdtTestSupport.parse("pkg/A.java",
                "package pkg;" +
                        "@Deprecated public class A {" +
                        "  @SuppressWarnings(\"x\") private int n;" +
                        "  @Override public String toString(@Deprecated Object o){ return \"\"; }" +
                        "}");
        ExtractionResult r = new ExtractionResult();
        new AnnotationExtractor(ctx).extract(cu, r);

        Set<String> fqns = r.annotations.stream()
                .map(a -> a.annotationFqn).collect(Collectors.toSet());
        assertTrue(fqns.contains("java.lang.Deprecated"), "got " + fqns);
        assertTrue(fqns.contains("java.lang.SuppressWarnings"), "got " + fqns);
        assertTrue(fqns.contains("java.lang.Override"), "got " + fqns);

        // The class-level @Deprecated, the field @SuppressWarnings, the
        // method @Override, and the parameter @Deprecated → 4 rows.
        assertEquals(4, r.annotations.size(), "got " + r.annotations.stream()
                .map(a -> a.annotationFqn + "@" + a.nodeId).collect(Collectors.toList()));

        Annotation suppress = r.annotations.stream()
                .filter(a -> a.annotationFqn.equals("java.lang.SuppressWarnings"))
                .findFirst().orElseThrow();
        assertTrue(suppress.attributes.contains("\"x\""),
                "expected value 'x' captured; got " + suppress.attributes);

        Annotation paramDeprecated = r.annotations.stream()
                .filter(a -> a.annotationFqn.equals("java.lang.Deprecated")
                        && a.nodeId.startsWith("pkg.A#toString"))
                .findFirst().orElseThrow();
        assertTrue(paramDeprecated.attributes.contains("\"_param\":0"),
                "param marker missing; got " + paramDeprecated.attributes);
    }
}
