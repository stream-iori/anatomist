package com.anatomist.extract;

import com.anatomist.core.JavaParserTestSupport;
import com.anatomist.core.NodeIdGenerator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AstEnclosingTest {

    @Test
    void repeatedChildrenReuseCallableId() {
        var unit = JavaParserTestSupport.parse(
                "package p; class A { void run() { first(); second(); }"
                        + " void first() {} void second() {} }");
        var calls = unit.findAll(MethodCallExpr.class);
        AstEnclosing enclosing = new AstEnclosing(new NodeIdGenerator());

        assertEquals("p.A#run()", enclosing.ownerIdOf(calls.get(0)));
        assertEquals(1, enclosing.cachedEntityCount());
        assertEquals("p.A#run()", enclosing.ownerIdOf(calls.get(1)));
        assertEquals(1, enclosing.cachedEntityCount());
    }
}
