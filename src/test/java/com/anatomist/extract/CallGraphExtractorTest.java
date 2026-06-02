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

import static org.junit.jupiter.api.Assertions.*;

class CallGraphExtractorTest {

    private final ExtractionContext ctx = new ExtractionContext(
            Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN");

    @Test
    void instanceCallToProjectInternalMethod() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A {\n"
                + "  void caller() { callee(); }\n"
                + "  void callee() {}\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new CallGraphExtractor(ctx).extract(cu, r);

        boolean internalCall = r.edges.stream().anyMatch(e ->
                "CALLS".equals(e.relation)
                        && "INSTANCE".equals(e.callKind)
                        && "pkg.A#caller()".equals(e.sourceId)
                        && "pkg.A#callee()".equals(e.targetId)
                        && !e.isExternal);
        assertTrue(internalCall, "internal INSTANCE call missing; got " + r.edges);
    }

    @Test
    void staticCallToExternal() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A {\n"
                + "  int abs() { return Math.abs(-1); }\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new CallGraphExtractor(ctx).extract(cu, r);

        Edge call = r.edges.stream()
                .filter(e -> "CALLS".equals(e.relation) && e.isExternal)
                .findFirst().orElseThrow(() -> new AssertionError("got " + r.edges));
        assertEquals("STATIC", call.callKind);
        assertEquals("java.lang.Math#abs(int)", call.externalTargetFqn);
    }

    @Test
    void constructorCall() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A {\n"
                + "  Object make() { return new Object(); }\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new CallGraphExtractor(ctx).extract(cu, r);

        Edge ctor = r.edges.stream()
                .filter(e -> "CALLS".equals(e.relation) && "CONSTRUCTOR".equals(e.callKind))
                .findFirst().orElseThrow();
        assertTrue(ctor.isExternal);
        assertEquals("java.lang.Object#Object()", ctor.externalTargetFqn);
    }

    @Test
    void enclosingId_lambdaBodyCall_attributesToLambdaNode() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; import java.util.function.Supplier;"
                + "public class A {"
                + "  String s;"
                + "  void run() {"
                + "    Supplier<Integer> sup = () -> Math.abs(-1);"
                + "  }"
                + "}");
        ExtractionResult r = new ExtractionResult();
        new CallGraphExtractor(ctx).extract(cu, r);

        Edge mathAbs = r.edges.stream()
                .filter(e -> "CALLS".equals(e.relation) && e.isExternal
                        && "java.lang.Math#abs(int)".equals(e.externalTargetFqn))
                .findFirst().orElseThrow(() -> new AssertionError("no Math.abs call; got " + r.edges));
        assertTrue(mathAbs.sourceId.startsWith("pkg.A#run()$lambda@L"),
                "call inside lambda must attribute to LAMBDA node; got source=" + mathAbs.sourceId);
    }

    @Test
    void context_recordedForCallInsideLoopAndBranch() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A {\n"
                + "  void caller(int[] xs){\n"
                + "    for (int i=0;i<xs.length;i++){\n"
                + "      if (xs[i] > 0) { callee(); }\n"
                + "    }\n"
                + "  }\n"
                + "  void callee(){}\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new CallGraphExtractor(ctx).extract(cu, r);

        Edge call = r.edges.stream()
                .filter(e -> "CALLS".equals(e.relation) && "pkg.A#callee()".equals(e.targetId))
                .findFirst().orElseThrow(() -> new AssertionError("got " + r.edges));
        assertEquals("for@L4>if-then@L5", call.context);
    }

    @Test
    void context_nullForUnconditionalCall() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A {\n"
                + "  void caller(){ callee(); }\n"
                + "  void callee(){}\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new CallGraphExtractor(ctx).extract(cu, r);

        Edge call = r.edges.stream()
                .filter(e -> "CALLS".equals(e.relation) && "pkg.A#callee()".equals(e.targetId))
                .findFirst().orElseThrow();
        assertNull(call.context);
    }
}
