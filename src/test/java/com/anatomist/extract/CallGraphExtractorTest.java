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

class CallGraphExtractorTest {

    private final ExtractionContext ctx = new ExtractionContext(
            Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN");

    @Test
    void extract_distinguishesCallKinds() {
        CompilationUnit cu = JdtTestSupport.parse("pkg/All.java",
                "package pkg;" +
                        "class B { static void s(){} void inst(){} } " +
                        "class P { void p(){} } " +
                        "class A extends P {" +
                        "  void f(){ new B(); B.s(); new B().inst(); super.p(); h(); }" +
                        "  void h(){}" +
                        "}");
        ExtractionResult r = new ExtractionResult();
        new CallGraphExtractor(ctx).extract(cu, r);

        Set<String> kinds = r.edges.stream()
                .filter(e -> "CALLS".equals(e.relation))
                .map(e -> e.callKind).collect(Collectors.toSet());
        assertTrue(kinds.contains("CONSTRUCTOR"), kinds.toString());
        assertTrue(kinds.contains("STATIC"), kinds.toString());
        assertTrue(kinds.contains("INSTANCE"), kinds.toString());
        assertTrue(kinds.contains("SUPER"), kinds.toString());
    }

    @Test
    void extract_emitsExternalEdgeForJdkCall() {
        CompilationUnit cu = JdtTestSupport.parse("pkg/A.java",
                "package pkg; public class A { void f(){ java.util.Objects.requireNonNull(this); } }");
        ExtractionResult r = new ExtractionResult();
        new CallGraphExtractor(ctx).extract(cu, r);

        Edge ext = r.edges.stream()
                .filter(e -> "CALLS".equals(e.relation) && e.isExternal)
                .findFirst().orElseThrow();
        assertEquals("STATIC", ext.callKind);
        assertEquals("java.util.Objects#requireNonNull(java.lang.Object)", ext.externalTargetFqn);
        assertNull(ext.targetId);
    }
}
