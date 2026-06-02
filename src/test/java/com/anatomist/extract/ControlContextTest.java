package com.anatomist.extract;

import com.anatomist.core.JavaParserTestSupport;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ControlContextTest {

    /** Find the first MethodCallExpr whose name matches, return its context. */
    private static String ctxOfCall(CompilationUnit cu, String calleeName) {
        MethodCallExpr call = cu.findAll(MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals(calleeName))
                .findFirst().orElseThrow(() -> new AssertionError("no call to " + calleeName));
        return ControlContext.of(call);
    }

    @Test
    void topLevelCall_hasNoContext() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package p; class A { void m(){ sink(); } void sink(){} }");
        assertNull(ctxOfCall(cu, "sink"));
    }

    @Test
    void ifThen_and_ifElse_distinguished() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package p; class A {\n"
              + "  void m(boolean b){\n"
              + "    if (b) { a(); } else { c(); }\n"
              + "  }\n"
              + "  void a(){} void c(){}\n"
              + "}");
        assertEquals("if-then@L3", ctxOfCall(cu, "a"));
        assertEquals("if-else@L3", ctxOfCall(cu, "c"));
    }

    @Test
    void conditionExpr_isUnconditional_noContext() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package p; class A {\n"
              + "  void m(){ if (cond()) {} }\n"
              + "  boolean cond(){ return true; }\n"
              + "}");
        assertNull(ctxOfCall(cu, "cond"));
    }

    @Test
    void nestedForIfElse_outerToInner() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package p; class A {\n"
              + "  void m(int[] xs){\n"
              + "    for (int i=0;i<xs.length;i++){\n"
              + "      if (xs[i] > 0) { pos(); } else { neg(); }\n"
              + "    }\n"
              + "  }\n"
              + "  void pos(){} void neg(){}\n"
              + "}");
        assertEquals("for@L3>if-then@L4", ctxOfCall(cu, "pos"));
        assertEquals("for@L3>if-else@L4", ctxOfCall(cu, "neg"));
    }

    @Test
    void foreach_while_do() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package p; import java.util.List; class A {\n"
              + "  void fe(List<String> l){ for (String s : l) { f1(); } }\n"
              + "  void wh(){ while (g()) { f2(); } }\n"
              + "  void dw(){ do { f3(); } while (g()); }\n"
              + "  boolean g(){ return false; }\n"
              + "  void f1(){} void f2(){} void f3(){}\n"
              + "}");
        assertEquals("foreach@L2", ctxOfCall(cu, "f1"));
        assertEquals("while@L3", ctxOfCall(cu, "f2"));
        assertEquals("do@L4", ctxOfCall(cu, "f3"));
    }

    @Test
    void tryCatchFinally_distinguished() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package p; class A {\n"
              + "  void m(){\n"
              + "    try { t(); } catch (RuntimeException e) { c(); } finally { f(); }\n"
              + "  }\n"
              + "  void t(){} void c(){} void f(){}\n"
              + "}");
        assertEquals("try@L3", ctxOfCall(cu, "t"));
        assertEquals("catch@L3", ctxOfCall(cu, "c"));
        assertEquals("finally@L3", ctxOfCall(cu, "f"));
    }

    @Test
    void switchEntry_caseAndDefault() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package p; class A {\n"
              + "  void m(int x){\n"
              + "    switch (x) {\n"
              + "      case 1: one(); break;\n"
              + "      default: other();\n"
              + "    }\n"
              + "  }\n"
              + "  void one(){} void other(){}\n"
              + "}");
        assertEquals("case@L4", ctxOfCall(cu, "one"));
        assertEquals("default@L5", ctxOfCall(cu, "other"));
    }

    @Test
    void ternary_thenAndElse() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package p; class A {\n"
              + "  int m(boolean b){ return b ? t() : e(); }\n"
              + "  int t(){ return 1; } int e(){ return 2; }\n"
              + "}");
        assertEquals("ternary-then@L2", ctxOfCall(cu, "t"));
        assertEquals("ternary-else@L2", ctxOfCall(cu, "e"));
    }

    @Test
    void synchronizedBlock() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package p; class A {\n"
              + "  final Object lock = new Object();\n"
              + "  void m(){ synchronized (lock) { work(); } }\n"
              + "  void work(){}\n"
              + "}");
        assertEquals("synchronized@L3", ctxOfCall(cu, "work"));
    }

    @Test
    void contextRelativeToLambdaOwner_notOuterMethod() {
        // The call inside the lambda body should start its context from the
        // lambda, not include the enclosing method's for-loop.
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package p; import java.util.List; class A {\n"
              + "  void m(List<String> l){\n"
              + "    for (int i=0;i<1;i++){\n"
              + "      l.forEach(s -> { if (s.isEmpty()) { inner(); } });\n"
              + "    }\n"
              + "  }\n"
              + "  void inner(){}\n"
              + "}");
        assertEquals("if-then@L4", ctxOfCall(cu, "inner"));
    }
}
