package com.anatomist.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownFormatterTest {

    @Test
    void formatNode_includesKeySections() {
        EnrichResult r = nodeResult("OrderService", "CLASS",
                "com.example.shop.service.OrderService");
        NodeRow field = new NodeRow();
        field.label = "orderRepository"; field.kind = "FIELD";
        field.qualifiedName = "com.x.OrderService#orderRepository";
        NodeRow method = new NodeRow();
        method.label = "createOrder"; method.kind = "METHOD";
        method.qualifiedName = "com.x.OrderService#createOrder";
        r.members.add(field);
        r.members.add(method);

        String md = MarkdownFormatter.format(r);

        assertTrue(md.contains("# OrderService"), "header should include label");
        assertTrue(md.contains("(CLASS)"), "kind should appear");
        assertTrue(md.contains("## Fields"));
        assertTrue(md.contains("## Methods"));
        assertTrue(md.contains("## Semantic Annotations"));
        assertTrue(md.contains("## Call Graph"));
        assertTrue(md.contains("## Suggested Queries"));
    }

    @Test
    void formatPackage_includesKeySections() {
        EnrichResult r = new EnrichResult();
        r.pkg = "com.example.shop.service";
        NodeRow t = type("OrderService", "com.example.shop.service.OrderService");
        r.members.add(t);
        r.suggestedQueries.add("anatomist package-deps");

        String md = MarkdownFormatter.format(r);
        assertTrue(md.contains("# com.example.shop.service"));
        assertTrue(md.contains("OrderService"));
        assertTrue(md.contains("## Suggested Queries"));
    }

    @Test
    void formatNode_truncatesLongCalleeList() {
        EnrichResult r = nodeResult("Big", "CLASS", "com.x.Big");
        for (int i = 0; i < 1000; i++) {
            EdgeRow e = new EdgeRow();
            e.source = "com.x.Big#m" + i + "()";
            e.target = "com.x.Other#m" + i + "()";
            e.relation = "CALLS";
            e.depth = 1;
            r.callees.add(e);
        }
        String md = MarkdownFormatter.format(r);
        long lines = md.lines().count();
        assertTrue(lines <= MarkdownFormatter.MAX_LINES + 5,
                "output should respect MAX_LINES; got " + lines);
        assertTrue(md.contains("truncated"),
                "truncated callee block should be annotated");
    }

    @Test
    void formatNode_emitsSemanticAnnotationTable() {
        EnrichResult r = nodeResult("OrderService", "CLASS",
                "com.example.shop.service.OrderService");
        SemanticAnnotationRow sa = new SemanticAnnotationRow();
        sa.category = "BUSINESS_SERVICE";
        sa.businessLabel = "订单服务";
        sa.source = "LLM";
        sa.confidence = "MEDIUM";
        r.semanticAnnotations.add(sa);

        String md = MarkdownFormatter.format(r);
        assertTrue(md.contains("BUSINESS_SERVICE"));
        assertTrue(md.contains("订单服务"));
        assertTrue(md.contains("LLM"));
    }

    private static EnrichResult nodeResult(String label, String kind, String fqn) {
        EnrichResult r = new EnrichResult();
        r.node = new NodeRow();
        r.node.id = fqn;
        r.node.label = label;
        r.node.kind = kind;
        r.node.qualifiedName = fqn;
        r.node.sourceFile = "X.java";
        r.suggestedQueries = List.of("anatomist callers-of " + fqn,
                "anatomist callees-of " + fqn);
        return r;
    }

    private static NodeRow type(String label, String fqn) {
        NodeRow n = new NodeRow();
        n.label = label; n.kind = "CLASS"; n.qualifiedName = fqn; n.id = fqn;
        return n;
    }
}
