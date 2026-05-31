package com.anatomist.core;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NodeIdGeneratorTest {

    private final NodeIdGenerator gen = new NodeIdGenerator();

    @Test
    void forType_preservesCase() {
        CompilationUnit cu = JavaParserTestSupport.parse("""
                package pkg;
                public class Order {
                    public static class Sub {}
                }
                """);
        ResolvedReferenceTypeDeclaration order = JavaParserTestSupport.resolveType(cu, "Order");
        ResolvedReferenceTypeDeclaration sub   = JavaParserTestSupport.resolveType(cu, "Sub");

        String orderId = gen.forType(order);
        String subId = gen.forType(sub);

        assertEquals("pkg.Order", orderId);
        assertTrue(subId.startsWith("pkg.Order"), "nested type FQN should include outer: " + subId);
        assertTrue(subId.endsWith("Sub"));
        assertNotEquals(orderId.toLowerCase(), orderId, "Order should preserve case");
    }

    @Test
    void forMethod_usesErasedSignature() {
        CompilationUnit cu = JavaParserTestSupport.parse("""
                package pkg;
                import java.util.List;
                public class A {
                    public void foo(String s, List<Integer> xs) {}
                    public void foo() {}
                }
                """);
        List<ResolvedMethodDeclaration> foos = JavaParserTestSupport.resolveMethods(cu, "A", "foo");
        assertEquals(2, foos.size());

        String withArgs = foos.stream()
                .filter(m -> m.getNumberOfParams() == 2)
                .map(gen::forMethod)
                .findFirst().orElseThrow();
        String noArgs = foos.stream()
                .filter(m -> m.getNumberOfParams() == 0)
                .map(gen::forMethod)
                .findFirst().orElseThrow();

        assertEquals("pkg.A#foo(java.lang.String,java.util.List)", withArgs);
        assertEquals("pkg.A#foo()", noArgs);
    }

    @Test
    void forLambda_concatsParentWithLineColumn() {
        assertEquals("pkg.A#foo()$lambda@L12C34",
                NodeIdGenerator.forLambda("pkg.A#foo()", 12, 34));
    }

    @Test
    void forMethodRef_concatsParentWithLineColumn() {
        assertEquals("pkg.A#foo()$methodref@L5C9",
                NodeIdGenerator.forMethodRef("pkg.A#foo()", 5, 9));
    }

    @Test
    void forLambda_isStableAcrossCalls() {
        String a = NodeIdGenerator.forLambda("pkg.A#bar()", 7, 3);
        String b = NodeIdGenerator.forLambda("pkg.A#bar()", 7, 3);
        assertEquals(a, b);
    }
}
