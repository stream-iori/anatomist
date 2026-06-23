package com.anatomist.semantic;

import com.anatomist.model.Annotation;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.anatomist.model.SemanticAnnotation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SemanticPostProcessorTest {

    @ParameterizedTest
    @CsvSource({
            "org.springframework.stereotype.Service,                       BUSINESS_SERVICE",
            "org.springframework.stereotype.Repository,                    DATA_ACCESS",
            "org.springframework.web.bind.annotation.RestController,       API_ENDPOINT",
            "org.springframework.stereotype.Controller,                    API_ENDPOINT",
            "jakarta.persistence.Entity,                                   PERSISTENCE_ENTITY",
            "org.springframework.transaction.annotation.Transactional,     TRANSACTION_BOUNDARY",
            "org.springframework.stereotype.Component,                     FRAMEWORK_COMPONENT"
    })
    void conventionRule_annotation_matchesAndWritesMedium(String fqn, String expectedCategory) {
        ExtractionResult r = new ExtractionResult();
        r.nodes.add(typeNode("com.x.Foo", "Foo", "CLASS"));
        r.annotations.add(annotation("com.x.Foo", fqn));

        new SemanticPostProcessor().process(r);

        SemanticAnnotation sa = first(r.semanticAnnotations, expectedCategory, "CONVENTION");
        assertEquals("com.x.Foo", sa.nodeId);
        assertEquals("MEDIUM", sa.confidence);
    }

    @ParameterizedTest
    @CsvSource({
            "OrderService,        BUSINESS_SERVICE",
            "OrderDTO,            DTO",
            "OrderRequest,        DTO",
            "OrderResponse,       DTO",
            "OrderRepository,     DATA_ACCESS",
            "OrderDao,            DATA_ACCESS",
            "OrderController,     API_ENDPOINT",
            "AppConfig,           CONFIGURATION",
            "AppConfiguration,    CONFIGURATION"
    })
    void conventionRule_naming_matchesAndWritesMedium(String label, String expectedCategory) {
        ExtractionResult r = new ExtractionResult();
        r.nodes.add(typeNode("com.x." + label, label, "CLASS"));

        new SemanticPostProcessor().process(r);

        SemanticAnnotation sa = first(r.semanticAnnotations, expectedCategory, "CONVENTION");
        assertEquals("com.x." + label, sa.nodeId);
        assertEquals("MEDIUM", sa.confidence);
    }

    @Test
    void multiRulesHit_writesAllRecords() {
        // S3: @Service-annotated class that also ends with "Service"
        ExtractionResult r = new ExtractionResult();
        r.nodes.add(typeNode("com.x.OrderService", "OrderService", "CLASS"));
        r.annotations.add(annotation("com.x.OrderService", "org.springframework.stereotype.Service"));

        new SemanticPostProcessor().process(r);

        long businessSvc = r.semanticAnnotations.stream()
                .filter(s -> "BUSINESS_SERVICE".equals(s.category) && "CONVENTION".equals(s.source))
                .count();
        assertEquals(2, businessSvc, "both annotation and naming rules should fire");
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
    void namingRule_skipsMethodAndField() {
        // BR-004: naming rules only apply to CLASS/INTERFACE/ENUM/RECORD
        ExtractionResult r = new ExtractionResult();
        r.nodes.add(typeNode("com.x.A#fooService()", "fooService", "METHOD"));
        r.nodes.add(typeNode("com.x.A.barService",   "barService", "FIELD"));

        new SemanticPostProcessor().process(r);

        assertTrue(r.semanticAnnotations.stream().noneMatch(s -> "CONVENTION".equals(s.source)),
                "naming rules must not fire on METHOD/FIELD");
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

    private static Annotation annotation(String nodeId, String fqn) {
        Annotation a = new Annotation();
        a.nodeId = nodeId;
        a.annotationFqn = fqn;
        return a;
    }

    private static SemanticAnnotation first(List<SemanticAnnotation> all, String category, String source) {
        Optional<SemanticAnnotation> opt = all.stream()
                .filter(s -> category.equals(s.category) && source.equals(s.source))
                .findFirst();
        assertTrue(opt.isPresent(),
                "expected SemanticAnnotation category=" + category + " source=" + source
                        + " but got " + all.size() + " annotations");
        return opt.get();
    }
}
