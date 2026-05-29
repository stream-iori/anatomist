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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceExtractorTest {

    private final ExtractionContext ctx = new ExtractionContext(
            Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN");

    @Test
    void extract_emitsFieldTypeReference() {
        CompilationUnit cu = JdtTestSupport.parse("pkg/A.java",
                "package pkg; class Order {} public class A { Order o; }");
        ExtractionResult r = new ExtractionResult();
        new ReferenceExtractor(ctx).extract(cu, r);

        Edge fieldRef = r.edges.stream()
                .filter(e -> "REFERENCES".equals(e.relation)
                        && "field_type".equals(e.context))
                .findFirst().orElseThrow();
        assertEquals("pkg.A#o", fieldRef.sourceId);
        assertEquals("pkg.Order", fieldRef.targetId);
        assertFalse(fieldRef.isExternal);
    }

    @Test
    void extract_emitsParameterReturnTypeReferences() {
        CompilationUnit cu = JdtTestSupport.parse("pkg/A.java",
                "package pkg; class Order {} public class A { Order f(Order x){ return x; } }");
        ExtractionResult r = new ExtractionResult();
        new ReferenceExtractor(ctx).extract(cu, r);

        long params = r.edges.stream()
                .filter(e -> "REFERENCES".equals(e.relation)
                        && "parameter_type".equals(e.context)).count();
        long returns = r.edges.stream()
                .filter(e -> "REFERENCES".equals(e.relation)
                        && "return_type".equals(e.context)).count();
        assertEquals(1, params);
        assertEquals(1, returns);
    }

    @Test
    void extract_emitsGenericArgReference() {
        CompilationUnit cu = JdtTestSupport.parse("pkg/A.java",
                "package pkg; import java.util.Map; import java.util.List;" +
                        "class Order {} public class A { Map<String, List<Order>> orders; }");
        ExtractionResult r = new ExtractionResult();
        new ReferenceExtractor(ctx).extract(cu, r);

        Set<String> internalTargets = r.edges.stream()
                .filter(e -> "REFERENCES".equals(e.relation) && !e.isExternal)
                .map(e -> e.targetId).collect(Collectors.toSet());
        assertTrue(internalTargets.contains("pkg.Order"),
                "should emit Order via generic_arg; got " + internalTargets);
    }

    @Test
    void extract_skipsExternalTypes() {
        CompilationUnit cu = JdtTestSupport.parse("pkg/A.java",
                "package pkg; import java.util.List; public class A { List<String> xs; }");
        ExtractionResult r = new ExtractionResult();
        new ReferenceExtractor(ctx).extract(cu, r);

        long internal = r.edges.stream()
                .filter(e -> "REFERENCES".equals(e.relation) && !e.isExternal).count();
        assertEquals(0, internal, "got " + r.edges);
    }
}
