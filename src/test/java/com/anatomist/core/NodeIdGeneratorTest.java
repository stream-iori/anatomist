package com.anatomist.core;

import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.anatomist.core.JdtTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class NodeIdGeneratorTest {

    private final NodeIdGenerator gen = new NodeIdGenerator();

    @Test
    void forType_preservesCase() {
        String src = """
                package pkg;
                public class Order {
                    public static class Sub {}
                }
                """;
        CompilationUnit cu = parse("pkg/Order.java", src);
        ITypeBinding order = bindingOf(cu, "Order");
        ITypeBinding sub = bindingOf(cu, "Sub");
        assertNotNull(order, "Order binding null");
        assertNotNull(sub, "Sub binding null");

        String orderId = gen.forType(order);
        String subId = gen.forType(sub);

        assertEquals("pkg.Order", orderId);
        assertTrue(subId.startsWith("pkg.Order"), "nested type FQN should include outer: " + subId);
        assertTrue(subId.endsWith("Sub"));
        assertNotEquals(orderId.toLowerCase(), orderId, "Order should preserve case");
    }

    @Test
    void forMethod_usesErasedSignature() {
        String src = """
                package pkg;
                import java.util.List;
                public class A {
                    public void foo(String s, List<Integer> xs) {}
                    public void foo() {}
                }
                """;
        CompilationUnit cu = parse("pkg/A.java", src);
        List<IMethodBinding> foos = methodBindings(cu, "A", "foo");
        assertEquals(2, foos.size());

        String withArgs = foos.stream()
                .filter(m -> m.getParameterTypes().length == 2)
                .map(gen::forMethod)
                .findFirst().orElseThrow();
        String noArgs = foos.stream()
                .filter(m -> m.getParameterTypes().length == 0)
                .map(gen::forMethod)
                .findFirst().orElseThrow();

        assertEquals("pkg.A#foo(java.lang.String,java.util.List)", withArgs);
        assertEquals("pkg.A#foo()", noArgs);
    }
}
