package com.anatomist.incremental;

import com.anatomist.model.Node;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SymbolGraphDeltaTest {

    @Test
    void bodyOnlyChangeHasNoSymbolImpact() {
        Node before = method("p.B#foo()", "p.B#foo", "{\"returnType\":\"void\"}");
        Node after = method("p.B#foo()", "p.B#foo", "{\"returnType\":\"void\"}");

        SymbolGraphDelta.Impact impact = SymbolGraphDelta.analyze(map(before), List.of(after));

        assertTrue(impact.exactTargetIds().isEmpty());
        assertTrue(impact.ownerTargetIds().isEmpty());
        assertTrue(impact.externalPrefixes().isEmpty());
    }

    @Test
    void returnTypeChangeImpactsExactCallers() {
        Node before = method("p.B#foo()", "p.B#foo", "{\"returnType\":\"java.lang.String\"}");
        Node after = method("p.B#foo()", "p.B#foo", "{\"returnType\":\"java.lang.Object\"}");

        SymbolGraphDelta.Impact impact = SymbolGraphDelta.analyze(map(before), List.of(after));

        assertEquals(java.util.Set.of(before.id), impact.exactTargetIds());
        assertTrue(impact.contractChangedIds().contains(before.id));
    }

    @Test
    void overloadAdditionImpactsExistingOverloadFamily() {
        Node owner = type("p.B", "CLASS");
        Node existing = method("p.B#foo(int)", "p.B#foo", "{\"returnType\":\"void\"}");
        Node added = method("p.B#foo(java.lang.String)", "p.B#foo", "{\"returnType\":\"void\"}");

        SymbolGraphDelta.Impact impact = SymbolGraphDelta.analyze(
                map(owner, existing), List.of(owner, existing, added));

        assertTrue(impact.exactTargetIds().contains(existing.id));
        assertTrue(impact.externalPrefixes().contains("p.B#foo("));
    }

    @Test
    void interfaceMethodAdditionImpactsImplementors() {
        Node owner = type("p.Contract", "INTERFACE");
        Node added = method("p.Contract#run()", "p.Contract#run", "{\"returnType\":\"void\"}");

        SymbolGraphDelta.Impact impact = SymbolGraphDelta.analyze(map(owner), List.of(owner, added));

        assertTrue(impact.ownerTargetIds().isEmpty());
        assertEquals(java.util.Set.of(owner.id), impact.implementorTargetIds());
    }

    @Test
    void addedTypeUsesExactTypeAndMemberBoundary() {
        Node added = type("p.External", "CLASS");

        SymbolGraphDelta.Impact impact = SymbolGraphDelta.analyze(Map.of(), List.of(added));

        assertEquals(java.util.Set.of("p.External"), impact.externalExactTargets());
        assertEquals(java.util.Set.of("p.External#"), impact.externalPrefixes());
    }

    private static Map<String, Node> map(Node... nodes) {
        Map<String, Node> out = new LinkedHashMap<>();
        for (Node node : nodes) out.put(node.id, node);
        return out;
    }

    private static Node type(String symbol, String kind) {
        Node node = base(symbol, symbol, kind);
        node.metadata = "{}";
        return node;
    }

    private static Node method(String symbol, String qualifiedName, String metadata) {
        Node node = base(symbol, qualifiedName, "METHOD");
        node.metadata = metadata;
        return node;
    }

    private static Node base(String symbol, String qualifiedName, String kind) {
        Node node = new Node();
        node.id = "m::MAIN::" + symbol;
        node.symbolId = symbol;
        node.qualifiedName = qualifiedName;
        node.label = qualifiedName;
        node.kind = kind;
        node.pkg = "p";
        node.module = "m";
        node.scope = "MAIN";
        node.sourceFile = "src/" + symbol.replace('#', '_') + ".java";
        return node;
    }
}
