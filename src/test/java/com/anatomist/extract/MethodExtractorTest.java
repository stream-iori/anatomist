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

class MethodExtractorTest {

    private final ExtractionContext ctx = new ExtractionContext(
            Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN");

    @Test
    void extract_emitsMethodNodeAndContainsEdge() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; public class A { public void foo() {} }");
        ExtractionResult r = new ExtractionResult();
        new MethodExtractor(ctx).extract(cu, r);

        assertEquals(1, r.nodes.size());
        Node n = r.nodes.get(0);
        assertEquals("pkg.A#foo()", n.id);
        assertEquals("METHOD", n.kind);

        assertEquals(1, r.edges.size());
        Edge e = r.edges.get(0);
        assertEquals("pkg.A", e.sourceId);
        assertEquals("pkg.A#foo()", e.targetId);
        assertEquals("CONTAINS", e.relation);
        assertFalse(e.isExternal);
        assertNull(e.externalTargetFqn);
    }

    @Test
    void extract_distinguishesOverloads() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; public class A {"
                + "  public void foo() {}"
                + "  public void foo(String s) {}"
                + "}");
        ExtractionResult r = new ExtractionResult();
        new MethodExtractor(ctx).extract(cu, r);

        Set<String> ids = r.nodes.stream().map(n -> n.id).collect(Collectors.toSet());
        assertTrue(ids.contains("pkg.A#foo()"));
        assertTrue(ids.contains("pkg.A#foo(java.lang.String)"));
    }

    @Test
    void extract_handlesConstructorAndGenericList() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; import java.util.List;"
                + "public class A {"
                + "  public A() {}"
                + "  public void bar(List<Integer> xs) {}"
                + "}");
        ExtractionResult r = new ExtractionResult();
        new MethodExtractor(ctx).extract(cu, r);

        Set<String> ids = r.nodes.stream().map(n -> n.id).collect(Collectors.toSet());
        assertTrue(ids.contains("pkg.A#A()"), "constructor id; got " + ids);
        assertTrue(ids.contains("pkg.A#bar(java.util.List)"), "erased generic; got " + ids);

        Node ctor = r.nodes.stream().filter(n -> n.id.equals("pkg.A#A()")).findFirst().orElseThrow();
        assertTrue(ctor.metadata.contains("\"isConstructor\":true"));
    }

    @Test
    void methodMetadata_marksGetterAsAccessor() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; public class A {"
                + "  private String name;"
                + "  public String getName() { return name; }"
                + "  public boolean isActive() { return true; }"
                + "}");
        ExtractionResult r = new ExtractionResult();
        new MethodExtractor(ctx).extract(cu, r);

        Node getName = r.nodes.stream().filter(n -> n.id.equals("pkg.A#getName()")).findFirst().orElseThrow();
        Node isActive = r.nodes.stream().filter(n -> n.id.equals("pkg.A#isActive()")).findFirst().orElseThrow();
        assertTrue(getName.metadata.contains("\"isAccessor\":true"), "getName must be accessor; got " + getName.metadata);
        assertTrue(isActive.metadata.contains("\"isAccessor\":true"), "isActive (boolean) must be accessor; got " + isActive.metadata);
    }

    @Test
    void methodMetadata_marksSetterAsAccessor() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; public class A {"
                + "  private String name;"
                + "  public void setName(String n) { this.name = n; }"
                + "}");
        ExtractionResult r = new ExtractionResult();
        new MethodExtractor(ctx).extract(cu, r);

        Node setName = r.nodes.stream().filter(n -> n.id.equals("pkg.A#setName(java.lang.String)")).findFirst().orElseThrow();
        assertTrue(setName.metadata.contains("\"isAccessor\":true"), "setName must be accessor; got " + setName.metadata);
    }

    @Test
    void methodMetadata_marksNonAccessorAsFalse() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; public class A {"
                + "  public void process() {}"
                + "  public String isReady() { return \"\"; }"      // is + non-boolean return
                + "  public void getSomething() {}"                 // get + void return
                + "  public void setX() {}"                         // set + 0 params
                + "  public int get() { return 0; }"                // bare get
                + "}");
        ExtractionResult r = new ExtractionResult();
        new MethodExtractor(ctx).extract(cu, r);

        for (String id : List.of(
                "pkg.A#process()",
                "pkg.A#isReady()",
                "pkg.A#getSomething()",
                "pkg.A#setX()",
                "pkg.A#get()")) {
            Node n = r.nodes.stream().filter(x -> x.id.equals(id)).findFirst().orElseThrow(() -> new AssertionError("missing " + id));
            assertTrue(n.metadata.contains("\"isAccessor\":false"), id + " must be non-accessor; got " + n.metadata);
        }
    }

    @Test
    void visit_lambdaExpr_emitsLambdaNodeAndContainsEdge() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; import java.util.List; import java.util.function.Predicate;"
                + "public class A {"
                + "  void run(List<String> xs) {"
                + "    Predicate<String> p = s -> s.isEmpty();"
                + "  }"
                + "}");
        ExtractionResult r = new ExtractionResult();
        new MethodExtractor(ctx).extract(cu, r);

        Node lambda = r.nodes.stream().filter(n -> "LAMBDA".equals(n.kind)).findFirst()
                .orElseThrow(() -> new AssertionError("no LAMBDA node; got " + r.nodes));
        assertTrue(lambda.id.startsWith("pkg.A#run(java.util.List)$lambda@L"),
                "lambda id format; got " + lambda.id);
        assertTrue(r.edges.stream().anyMatch(e ->
                "CONTAINS".equals(e.relation)
                        && "pkg.A#run(java.util.List)".equals(e.sourceId)
                        && lambda.id.equals(e.targetId)),
                "CONTAINS edge from parent method missing; got " + r.edges);
    }

    @Test
    void visit_anonymousClassMethod_emitsMethodNodeContainedByAnonClass() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;"
                + "public class A {"
                + "  void outer() {"
                + "    new Runnable(){ public void run(){} };"
                + "  }"
                + "}");
        ExtractionResult r = new ExtractionResult();
        new MethodExtractor(ctx).extract(cu, r);

        String anonMethod = r.nodes.stream()
                .filter(n -> "METHOD".equals(n.kind))
                .map(n -> n.id)
                .filter(id -> id.startsWith("pkg.A#outer()$anon@L"))
                .filter(id -> id.endsWith("#run()"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("anonymous method node missing; got " + r.nodes));
        String anonClass = anonMethod.substring(0, anonMethod.indexOf("#run()"));
        assertTrue(r.edges.stream().anyMatch(e ->
                "CONTAINS".equals(e.relation)
                        && anonClass.equals(e.sourceId)
                        && anonMethod.equals(e.targetId)),
                "anonymous class must CONTAIN its method; got " + r.edges);
    }

    @Test
    void visit_unresolvedAnonymousClassMethod_emitsStableFallbackMethodNode() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;"
                + "public class A {"
                + "  void outer() {"
                + "    new Callback(){ public void done(Missing m){} };"
                + "  }"
                + "}"
                + "interface Callback { void done(Missing m); }");
        ExtractionResult r = new ExtractionResult();
        new MethodExtractor(ctx).extract(cu, r);

        String anonMethod = r.nodes.stream()
                .filter(n -> "METHOD".equals(n.kind))
                .map(n -> n.id)
                .filter(id -> id.startsWith("pkg.A#outer()$anon@L"))
                .filter(id -> id.endsWith("#done(<unresolved>)"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("fallback anonymous method node missing; got " + r.nodes));
        String anonClass = anonMethod.substring(0, anonMethod.indexOf("#done("));
        assertTrue(r.edges.stream().anyMatch(e ->
                "CONTAINS".equals(e.relation)
                        && anonClass.equals(e.sourceId)
                        && anonMethod.equals(e.targetId)),
                "fallback anonymous class must CONTAIN its method; got " + r.edges);
    }

    @Test
    void visit_nestedLambda_emitsDistinctIds() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; import java.util.function.Function;"
                + "public class A {"
                + "  Function<String, Function<String, String>> f = a -> b -> a + b;"
                + "}");
        ExtractionResult r = new ExtractionResult();
        new MethodExtractor(ctx).extract(cu, r);

        long lambdas = r.nodes.stream().filter(n -> "LAMBDA".equals(n.kind)).count();
        assertEquals(2, lambdas, "expected 2 LAMBDA nodes (outer + inner); got " + r.nodes);
        Set<String> ids = r.nodes.stream().filter(n -> "LAMBDA".equals(n.kind))
                .map(n -> n.id).collect(Collectors.toSet());
        assertEquals(2, ids.size(), "nested lambda ids must be distinct; got " + ids);
    }

    @Test
    void visit_methodReferenceExpr_emitsMethodRefNodeAndCallsEdge() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; import java.util.List; import java.util.stream.Stream;"
                + "public class A {"
                + "  static String upper(String s) { return s.toUpperCase(); }"
                + "  Stream<String> run(List<String> xs) { return xs.stream().map(A::upper); }"
                + "}");
        ExtractionResult r = new ExtractionResult();
        new MethodExtractor(ctx).extract(cu, r);

        Node methodRef = r.nodes.stream().filter(n -> "METHOD_REF".equals(n.kind)).findFirst()
                .orElseThrow(() -> new AssertionError("no METHOD_REF; got " + r.nodes));
        assertTrue(methodRef.id.contains("$methodref@L"), "id format; got " + methodRef.id);
        assertTrue(r.edges.stream().anyMatch(e ->
                "CONTAINS".equals(e.relation) && methodRef.id.equals(e.targetId)),
                "CONTAINS edge for METHOD_REF missing; got " + r.edges);
        assertTrue(r.edges.stream().anyMatch(e ->
                "CALLS".equals(e.relation)
                        && methodRef.id.equals(e.sourceId)
                        && !e.isExternal
                        && "pkg.A#upper(java.lang.String)".equals(e.targetId)),
                "CALLS edge from METHOD_REF to A.upper missing; got " + r.edges);
    }

    @Test
    void visit_methodReferenceExpr_resolveFailure_emitsNodeWithoutCallsEdge() {
        // Reference to an unknown identifier on an unresolved scope — SymbolSolver
        // can't resolve the target. We still expect a METHOD_REF Node, but no CALLS.
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; import java.util.function.Supplier;"
                + "public class A {"
                + "  Supplier<Object> s = unknownVar::doesNotExist;"
                + "}");
        ExtractionResult r = new ExtractionResult();
        new MethodExtractor(ctx).extract(cu, r);

        Node methodRef = r.nodes.stream().filter(n -> "METHOD_REF".equals(n.kind)).findFirst()
                .orElseThrow(() -> new AssertionError("no METHOD_REF; got " + r.nodes));
        assertTrue(methodRef.metadata.contains("\"bindingResolved\":false"),
                "bindingResolved=false expected; got " + methodRef.metadata);
        assertFalse(r.edges.stream().anyMatch(e ->
                "CALLS".equals(e.relation) && methodRef.id.equals(e.sourceId)),
                "no CALLS edge for unresolved METHOD_REF; got " + r.edges);
    }

    @Test
    void visit_constructorReference_gracefulDegrade() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg; import java.util.function.Supplier;"
                + "public class A {"
                + "  Supplier<Object> make = Object::new;"
                + "}");
        ExtractionResult r = new ExtractionResult();
        // Must not throw.
        new MethodExtractor(ctx).extract(cu, r);

        // METHOD_REF Node always emitted for graceful degrade.
        assertTrue(r.nodes.stream().anyMatch(n -> "METHOD_REF".equals(n.kind)),
                "constructor reference should still emit METHOD_REF Node; got " + r.nodes);
    }
}
