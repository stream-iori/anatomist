package com.anatomist.extract;

import com.anatomist.core.JavaParserTestSupport;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CallOverloadResolverTest {
    @Test
    void primitiveWideningPrefersNearestApplicableOverload() {
        var unit = JavaParserTestSupport.parse("""
                class Case {
                  void choose(long value) {}
                  void choose(double value) {}
                  void run(int value) { choose(value); }
                }
                """);
        MethodCallExpr call = unit.findFirst(MethodCallExpr.class).orElseThrow();
        List<MethodDeclaration> candidates = unit.findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().equals("choose")).toList();

        List<MethodDeclaration> selected = CallOverloadResolver.bestAst(candidates, call);

        assertEquals(1, selected.size());
        assertEquals("long", selected.getFirst().getParameter(0).getTypeAsString());
    }

    @Test
    void fixedArityWinsBeforeVarargs() {
        var unit = JavaParserTestSupport.parse("""
                class Case {
                  void choose(Object value) {}
                  void choose(String... value) {}
                  void run() { choose("x"); }
                }
                """);
        MethodCallExpr call = unit.findFirst(MethodCallExpr.class).orElseThrow();
        List<MethodDeclaration> candidates = unit.findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().equals("choose")).toList();

        List<MethodDeclaration> selected = CallOverloadResolver.bestAst(candidates, call);

        assertEquals(1, selected.size());
        assertEquals("Object", selected.getFirst().getParameter(0).getTypeAsString());
    }

    @Test
    void expandedVarargsAcceptsSeveralArguments() {
        var unit = JavaParserTestSupport.parse("""
                class Case {
                  void choose(String... values) {}
                  void run() { choose("a", "b"); }
                }
                """);
        MethodCallExpr call = unit.findFirst(MethodCallExpr.class).orElseThrow();
        MethodDeclaration candidate = unit.findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().equals("choose")).findFirst().orElseThrow();

        assertEquals(List.of(candidate), CallOverloadResolver.bestAst(List.of(candidate), call));
    }
}
