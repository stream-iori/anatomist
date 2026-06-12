package com.anatomist.semantic;

import com.anatomist.model.ArchRole;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.anatomist.model.Edge;
import com.anatomist.model.Annotation;
import com.anatomist.store.SqliteStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ArchRoleE2ETest {

    private SqliteStore store;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        store = new SqliteStore(tmp.resolve("e2e.db"));
        store.initSchema();
        seedFixture();
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void inferAndDetectSmells() {
        // Step 1: infer arch roles
        ArchRoleInferrer inferrer = new ArchRoleInferrer(store);
        List<ArchRole> roles = inferrer.infer();
        store.upsertArchRoles(roles);

        // Verify L1 inferences
        assertRole("com.example.OrderController", "ENTRY", roles);
        assertRole("com.example.OrderRepository", "REPOSITORY", roles);
        assertRole("com.example.AppConfig", "INFRASTRUCTURE", roles);
        assertRole("com.example.Order", "DOMAIN_MODEL", roles);

        // Verify L2 inferences: @Service → APPLICATION or DOMAIN_SERVICE
        Set<String> serviceRoles = roles.stream()
                .filter(r -> r.nodeId.equals("com.example.OrderService"))
                .map(r -> r.role)
                .collect(Collectors.toSet());
        assertFalse(serviceRoles.isEmpty(), "OrderService should have an inferred role");
        assertTrue(serviceRoles.contains("APPLICATION") || serviceRoles.contains("DOMAIN_SERVICE"),
                "OrderService should be APPLICATION or DOMAIN_SERVICE, got: " + serviceRoles);

        // Step 2: detect smells
        SmellDetector detector = new SmellDetector(store);
        List<SmellDetector.Smell> smells = detector.detect();

        // We seeded a layer bypass: OrderController calls OrderRepository directly
        boolean hasLayerBypass = smells.stream()
                .anyMatch(s -> "layer-bypass".equals(s.type));
        assertTrue(hasLayerBypass, "Should detect layer-bypass: ENTRY→REPOSITORY");
    }

    @Test
    void emptyDbProducesNoSmells() {
        SmellDetector detector = new SmellDetector(store);
        // No arch_roles → no smells
        assertTrue(detector.detect().isEmpty());
    }

    private void assertRole(String nodeId, String expectedRole, List<ArchRole> roles) {
        ArchRole match = roles.stream()
                .filter(r -> r.nodeId.equals(nodeId))
                .findFirst().orElse(null);
        assertNotNull(match, "Expected role for " + nodeId);
        assertEquals(expectedRole, match.role, "Role mismatch for " + nodeId);
    }

    private void seedFixture() {
        ExtractionResult r = new ExtractionResult();

        // Types
        r.nodes.add(typeNode("com.example.OrderController", "OrderController"));
        r.nodes.add(typeNode("com.example.OrderService", "OrderService"));
        r.nodes.add(typeNode("com.example.OrderRepository", "OrderRepository"));
        r.nodes.add(typeNode("com.example.Order", "Order"));
        r.nodes.add(typeNode("com.example.AppConfig", "AppConfig"));

        // Methods
        r.nodes.add(methodNode("com.example.OrderController#create()", "create"));
        r.nodes.add(methodNode("com.example.OrderService#createOrder()", "createOrder"));
        r.nodes.add(methodNode("com.example.OrderRepository#save()", "save"));

        // CONTAINS edges
        r.edges.add(containsEdge("com.example.OrderController", "com.example.OrderController#create()"));
        r.edges.add(containsEdge("com.example.OrderService", "com.example.OrderService#createOrder()"));
        r.edges.add(containsEdge("com.example.OrderRepository", "com.example.OrderRepository#save()"));

        // CALLS: Controller → Service (normal)
        r.edges.add(callEdge("com.example.OrderController#create()", "com.example.OrderService#createOrder()"));
        // CALLS: Controller → Repository (layer bypass!)
        r.edges.add(callEdge("com.example.OrderController#create()", "com.example.OrderRepository#save()"));
        // CALLS: Service → Repository (normal)
        r.edges.add(callEdge("com.example.OrderService#createOrder()", "com.example.OrderRepository#save()"));

        // Annotations
        r.annotations.add(ann("com.example.OrderController", "org.springframework.web.bind.annotation.RestController"));
        r.annotations.add(ann("com.example.OrderService", "org.springframework.stereotype.Service"));
        r.annotations.add(ann("com.example.OrderRepository", "org.springframework.stereotype.Repository"));
        r.annotations.add(ann("com.example.Order", "jakarta.persistence.Entity"));
        r.annotations.add(ann("com.example.AppConfig", "org.springframework.context.annotation.Configuration"));

        store.write(r);
    }

    private static Node typeNode(String id, String label) {
        Node n = new Node();
        n.id = id; n.label = label; n.kind = "CLASS";
        n.qualifiedName = id; n.sourceFile = label + ".java";
        n.pkg = "com.example"; n.scope = "MAIN";
        return n;
    }

    private static Node methodNode(String id, String label) {
        Node n = new Node();
        n.id = id; n.label = label; n.kind = "METHOD";
        n.qualifiedName = id; n.sourceFile = "Source.java"; n.scope = "MAIN";
        return n;
    }

    private static Edge containsEdge(String source, String target) {
        Edge e = new Edge();
        e.sourceId = source; e.targetId = target;
        e.relation = "CONTAINS"; e.isExternal = false;
        return e;
    }

    private static Edge callEdge(String source, String target) {
        Edge e = new Edge();
        e.sourceId = source; e.targetId = target;
        e.relation = "CALLS"; e.isExternal = false; e.callKind = "INSTANCE";
        return e;
    }

    private static Annotation ann(String nodeId, String fqn) {
        Annotation a = new Annotation();
        a.nodeId = nodeId; a.annotationFqn = fqn;
        return a;
    }
}
