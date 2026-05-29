package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.JdtTestSupport;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FieldExtractorTest {

    private final ExtractionContext ctx = new ExtractionContext(
            Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN");

    @Test
    void extract_emitsFieldNodeAndContainsEdge() {
        CompilationUnit cu = JdtTestSupport.parse("pkg/A.java",
                "package pkg; public class A { private int n; }");
        ExtractionResult r = new ExtractionResult();
        new FieldExtractor(ctx).extract(cu, r);

        assertEquals(1, r.nodes.size());
        Node n = r.nodes.get(0);
        assertEquals("pkg.A#n", n.id);
        assertEquals("FIELD", n.kind);
        assertEquals("n", n.label);

        assertEquals(1, r.edges.size());
        Edge e = r.edges.get(0);
        assertEquals("pkg.A", e.sourceId);
        assertEquals("pkg.A#n", e.targetId);
        assertEquals("CONTAINS", e.relation);
        assertFalse(e.isExternal);
    }

    @Test
    void extract_marksStaticFinal() {
        CompilationUnit cu = JdtTestSupport.parse("pkg/A.java",
                "package pkg; public class A { static final String S = \"\"; }");
        ExtractionResult r = new ExtractionResult();
        new FieldExtractor(ctx).extract(cu, r);

        Node s = r.nodes.stream().filter(x -> x.id.equals("pkg.A#S"))
                .findFirst().orElseThrow();
        assertTrue(s.metadata.contains("\"isStatic\":true"), s.metadata);
        assertTrue(s.metadata.contains("\"isFinal\":true"), s.metadata);
    }
}
