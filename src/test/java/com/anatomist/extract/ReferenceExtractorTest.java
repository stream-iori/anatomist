package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.JavaParserTestSupport;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceExtractorTest {

    private final ExtractionContext ctx = new ExtractionContext(
            Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN");

    @Test
    void emitsProjectInternalFieldType() {
        // Two top-level files in one CU isn't valid Java, so we put Order as a
        // nested class. Nested types are internal too.
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A {\n"
                + "  static class Order {}\n"
                + "  Order o;\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new ReferenceExtractor(ctx).extract(cu, r);

        boolean fieldRef = r.edges.stream()
                .anyMatch(e -> "REFERENCES".equals(e.relation)
                        && "field_type".equals(e.context)
                        && !e.isExternal
                        && "pkg.A#o".equals(e.sourceId)
                        && "pkg.A.Order".equals(e.targetId));
        assertTrue(fieldRef, "field_type REFERENCES missing; got " + r.edges);
    }

    @Test
    void emitsParameterAndReturnTypeReferences() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A {\n"
                + "  static class Req {}\n"
                + "  static class Res {}\n"
                + "  Res run(Req r) { return null; }\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new ReferenceExtractor(ctx).extract(cu, r);

        Set<String> seen = r.edges.stream()
                .filter(e -> "REFERENCES".equals(e.relation))
                .map(e -> e.context + "→" + e.targetId)
                .collect(Collectors.toSet());
        assertTrue(seen.contains("parameter_type→pkg.A.Req"), "got " + seen);
        assertTrue(seen.contains("return_type→pkg.A.Res"), "got " + seen);
    }

    @Test
    void recursesIntoGenericArgs() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "import java.util.List;\n"
                + "public class A {\n"
                + "  static class Order {}\n"
                + "  List<Order> orders;\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new ReferenceExtractor(ctx).extract(cu, r);

        boolean genericArg = r.edges.stream()
                .anyMatch(e -> "REFERENCES".equals(e.relation)
                        && "generic_arg".equals(e.context)
                        && "pkg.A.Order".equals(e.targetId));
        assertTrue(genericArg, "expected generic_arg REFERENCES; got " + r.edges);
    }

    @Test
    void visit_lambdaParameterType_emitsReferencesEdge() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; import java.util.function.Function;"
                + "public class A {"
                + "  static class Req {}"
                + "  static class Res {}"
                + "  Function<Req, Res> f = (Req r) -> new Res();"
                + "}");
        ExtractionResult r = new ExtractionResult();
        new ReferenceExtractor(ctx).extract(cu, r);

        boolean lambdaParamRef = r.edges.stream().anyMatch(e ->
                "REFERENCES".equals(e.relation)
                        && "parameter_type".equals(e.context)
                        && e.sourceId != null
                        && e.sourceId.contains("$lambda@L")
                        && "pkg.A.Req".equals(e.targetId));
        assertTrue(lambdaParamRef,
                "lambda parameter_type REFERENCES (source=LAMBDA id, target=Req) missing; got " + r.edges);
    }
}
