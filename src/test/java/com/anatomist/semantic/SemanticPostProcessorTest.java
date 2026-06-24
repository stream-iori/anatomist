package com.anatomist.semantic;

import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.anatomist.model.SemanticAnnotation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SemanticPostProcessorTest {

    @Test
    void conventionRules_doNotInferBusinessCategoriesFromAnnotationsOrNames() {
        ExtractionResult r = new ExtractionResult();
        r.nodes.add(typeNode("com.x.OrderService", "OrderService", "CLASS"));
        r.nodes.add(typeNode("com.x.OrderController", "OrderController", "CLASS"));

        new SemanticPostProcessor().process(r);

        assertTrue(r.semanticAnnotations.stream().noneMatch(s -> "CONVENTION".equals(s.source)),
                "post-processor must not infer entry/domain/business categories");
    }

    @Test
    void javadoc_extractsFirstParagraph() {
        // S5
        ExtractionResult r = new ExtractionResult();
        Node n = typeNode("com.x.OrderService", "OrderService", "CLASS");
        n.javadoc = "订单服务，负责处理订单的创建和支付\n\n@param order 订单\n@return 结果";
        r.nodes.add(n);

        new SemanticPostProcessor().process(r);

        SemanticAnnotation sa = r.semanticAnnotations.stream()
                .filter(s -> "JAVADOC".equals(s.source))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no JAVADOC annotation"));
        assertEquals("com.x.OrderService", sa.nodeId);
        assertEquals("HIGH", sa.confidence);
        assertEquals("订单服务，负责处理订单的创建和支付", sa.businessDescription);
    }

    @Test
    void javadocNull_skipped() {
        // S6
        ExtractionResult r = new ExtractionResult();
        Node n = typeNode("com.x.OrderItem", "OrderItem", "CLASS");
        n.javadoc = null;
        r.nodes.add(n);

        new SemanticPostProcessor().process(r);

        assertTrue(r.semanticAnnotations.stream().noneMatch(s -> "JAVADOC".equals(s.source)),
                "no JAVADOC for null-javadoc node");
    }

    @Test
    void conventionRules_doNotInferCategoriesForMethodsAndFields() {
        ExtractionResult r = new ExtractionResult();
        r.nodes.add(typeNode("com.x.A#fooService()", "fooService", "METHOD"));
        r.nodes.add(typeNode("com.x.A.barService",   "barService", "FIELD"));

        new SemanticPostProcessor().process(r);

        assertTrue(r.semanticAnnotations.stream().noneMatch(s -> "CONVENTION".equals(s.source)),
                "post-processor must not infer categories on METHOD/FIELD");
    }

    private static Node typeNode(String id, String label, String kind) {
        Node n = new Node();
        n.id = id;
        n.label = label;
        n.kind = kind;
        n.qualifiedName = id;
        n.sourceFile = "X.java";
        n.scope = "MAIN";
        return n;
    }

}
