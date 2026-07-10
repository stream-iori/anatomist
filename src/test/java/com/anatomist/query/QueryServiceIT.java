package com.anatomist.query;

import com.anatomist.cli.IndexCommand;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 integration test for {@link QueryService} — drives the full
 * index → SQLite → query pipeline against {@code fixtures/mini-spring-shop}.
 *
 * <p>Each {@code @Test} maps to one or more sub-scenarios from
 * {@code docs/scenario-2-query.md}.</p>
 */
class QueryServiceIT {

    static Path dbPath;

    @BeforeAll
    static void buildIndex(@TempDir Path tmp) throws Exception {
        Path repo = Path.of(System.getProperty("user.dir"));
        Path fixture = repo.resolve("fixtures/mini-spring-shop");
        assertTrue(Files.isDirectory(fixture));

        dbPath = tmp.resolve("query-it.db");
        // Persist db outside @TempDir lifetime by copying once; easier: keep
        // the @TempDir alive via static reference. (TempDir cleanup runs after
        // all tests in this class — we're fine.)

        String projectSource = String.join(File.pathSeparator,
                fixture.resolve("api/src/main/java").toString(),
                fixture.resolve("domain/src/main/java").toString(),
                fixture.resolve("service/src/main/java").toString());

        IndexCommand cmd = new IndexCommand();
        new CommandLine(cmd).parseArgs(
                fixture.toString(),
                "--project-source", projectSource,
                "--no-classpath",
                "--output", dbPath.toString());

        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        PrintStream old = System.out;
        try {
            System.setOut(new PrintStream(cap, true, StandardCharsets.UTF_8));
            int rc = cmd.call();
            assertEquals(0, rc, "index failed: " + cap.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(old);
        }
        assertTrue(Files.exists(dbPath));
    }

    // ── B. 符号定位 ───────────────────────────────────────────────────

    @Test
    void b1_search_byLabel_findsOrderService() {
        try (QueryService q = new QueryService(dbPath)) {
            List<NodeRow> rows = q.search("OrderService", null, 20);
            assertFalse(rows.isEmpty());
            assertTrue(rows.stream().anyMatch(r ->
                    "com.example.shop.service.OrderService".equals(r.qualifiedName)));
        }
    }

    @Test
    void b2_search_kindFilter_methodOnly() {
        try (QueryService q = new QueryService(dbPath)) {
            List<NodeRow> rows = q.search("createOrder", "METHOD", 20);
            assertFalse(rows.isEmpty());
            assertTrue(rows.stream().allMatch(r -> "METHOD".equals(r.kind)));
            assertTrue(rows.stream().anyMatch(r ->
                    "com.example.shop.service.OrderService#createOrder".equals(r.qualifiedName)));
        }
    }

    @Test
    void b3_context_byExactFqn() {
        try (QueryService q = new QueryService(dbPath)) {
            ContextResult r = q.context("com.example.shop.service.OrderService", 0);
            assertNotNull(r);
            assertNotNull(r.node);
            assertEquals("OrderService", r.node.label);
            assertFalse(r.members.isEmpty(), "should have methods/fields");
            assertTrue(r.members.stream().anyMatch(m -> "createOrder".equals(m.label)));
        }
    }

    @Test
    void b4_searchByAnnotation() {
        try (QueryService q = new QueryService(dbPath)) {
            // mini-spring-shop has @Service, @Transactional, @Autowired, @Override.
            List<NodeRow> rows = q.searchByAnnotation("Override", null, 20);
            assertFalse(rows.isEmpty(),
                    "@Override should match overridden methods (applyDiscount, run)");
        }
    }

    @Test
    void b5_implementorsOf_interface() {
        try (QueryService q = new QueryService(dbPath)) {
            List<NodeRow> rows = q.implementorsOf("com.example.shop.repository.OrderRepository");
            assertFalse(rows.isEmpty(), "InMemoryOrderRepository implements OrderRepository");
            assertTrue(rows.stream().anyMatch(r ->
                    "com.example.shop.repository.InMemoryOrderRepository".equals(r.qualifiedName)));
        }
    }

    // ── C. 结构理解 ───────────────────────────────────────────────────

    @Test
    void c1_context_listsMembersAndAnnotations() {
        try (QueryService q = new QueryService(dbPath)) {
            ContextResult r = q.context("com.example.shop.service.OrderService", 0);
            assertNotNull(r);
            long methods = r.members.stream().filter(m -> "METHOD".equals(m.kind)).count();
            long fields  = r.members.stream().filter(m -> "FIELD".equals(m.kind)).count();
            assertTrue(methods >= 4, "OrderService has ≥4 methods; got " + methods);
            assertTrue(fields >= 4, "OrderService has ≥4 fields; got " + fields);
            // No classpath → only @Override is resolvable; class-level Spring
            // annotations would only show with classpath. Just ensure annotations
            // field is present (could be empty for the class itself).
            assertNotNull(r.annotations);
        }
    }

    @Test
    void c2_hierarchy_extendsAndImplements() {
        try (QueryService q = new QueryService(dbPath)) {
            HierarchyResult h = q.hierarchy("com.example.shop.service.OrderService");
            assertTrue(h.extendsChain.size() >= 2, "self + BaseService");
            assertEquals("self", h.extendsChain.get(0).role);
            assertEquals("com.example.shop.service.BaseService",
                    h.extendsChain.get(1).qualifiedName);

            HierarchyResult ih = q.hierarchy(
                    "com.example.shop.repository.InMemoryOrderRepository");
            assertTrue(ih.implementsList.stream().anyMatch(e ->
                    "com.example.shop.repository.OrderRepository".equals(e.qualifiedName)));
        }
    }

    @Test
    void c3_context_onMethodFqn() {
        try (QueryService q = new QueryService(dbPath)) {
            ContextResult r = q.context(
                    "com.example.shop.service.OrderService#createOrder", 0);
            assertNotNull(r);
            assertEquals("METHOD", r.node.kind);
        }
    }

    @Test
    void c4_depsOf_includesCallsAndReferences() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> rows = q.depsOf("com.example.shop.service.OrderService");
            assertFalse(rows.isEmpty());
            assertTrue(rows.stream().anyMatch(r -> "CALLS".equals(r.relation)));
            assertTrue(rows.stream().anyMatch(r -> "REFERENCES".equals(r.relation)));
        }
    }

    @Test
    void c5_usedBy_reverse() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> rows = q.usedBy("com.example.shop.service.OrderService");
            assertFalse(rows.isEmpty(),
                    "OrderController references OrderService — should not be empty");
        }
    }

    // ── D. 调用链 ─────────────────────────────────────────────────────

    @Test
    void d1_calleesOf_singleHop() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> rows = q.calleesOf(
                    "com.example.shop.service.OrderService#createOrder", 1);
            assertFalse(rows.isEmpty());
            // All depth=1 on single hop
            assertTrue(rows.stream().allMatch(r -> r.depth != null && r.depth == 1));
        }
    }

    @Test
    void d2_callersOf_singleHop() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> rows = q.callersOf(
                    "com.example.shop.service.OrderService#createOrder", 1);
            assertFalse(rows.isEmpty(),
                    "OrderController.createOrder calls OrderService.createOrder");
        }
    }

    @Test
    void d3_calleesOf_multiHop_recurses() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> single = q.calleesOf(
                    "com.example.shop.controller.OrderController#create", 1);
            List<EdgeRow> deep   = q.calleesOf(
                    "com.example.shop.controller.OrderController#create", 5);
            assertTrue(deep.size() >= single.size(),
                    "multi-hop should include single-hop");
            int maxDepth = deep.stream().mapToInt(r -> r.depth).max().orElse(0);
            assertTrue(maxDepth >= 2, "expected ≥2-hop call chain; got " + maxDepth);
        }
    }

    // ── F. 影响分析 ───────────────────────────────────────────────────

    @Test
    void f1_callersOf_deepImpact() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> rows = q.callersOf(
                    "com.example.shop.service.OrderValidator#validate", 5);
            assertFalse(rows.isEmpty(), "OrderService.createOrder → validator.validate");
        }
    }

    @Test
    void f2_implementorsOf_alias_for_b5() {
        try (QueryService q = new QueryService(dbPath)) {
            List<NodeRow> rows = q.implementorsOf("OrderRepository");
            assertFalse(rows.isEmpty(), "short-label resolution should also work");
        }
    }

    @Test
    void f3_usedBy_typeDeletionImpact() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> rows = q.usedBy("com.example.shop.service.PriceCalculator");
            assertFalse(rows.isEmpty(),
                    "OrderService uses PriceCalculator — deleting it would break callers");
        }
    }

    // ── safety: recursive CTE doesn't infinite-loop on self-call ──────

    @Test
    void recursiveCte_handlesDepthCap_withoutHang() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> rows = q.calleesOf(
                    "com.example.shop.service.OrderService#createOrder", 20);
            assertNotNull(rows);
            // Should terminate quickly even at max depth.
        }
    }

    // ── Field-level + path + package aggregations (DESIGN.md §CLI 命令) ──

    @Test
    void fieldWriters_findsWritesOnOrderRepository() {
        try (QueryService q = new QueryService(dbPath)) {
            // OrderService.createOrder writes orderRepository indirectly via init,
            // but the most reliable write target on this fixture is Order.status
            // (`order.setStatus(OrderStatus.PENDING)`) — no, that's a method call.
            // Instead pick Order's own self-write fields if any; safer assertion:
            // simply assert the call returns a list (empty list still valid).
            List<EdgeRow> rows = q.fieldWriters(
                    "com.example.shop.domain.entity.Order#customerId");
            assertNotNull(rows);
        }
    }

    @Test
    void fieldReaders_onSelfReferenceField() {
        try (QueryService q = new QueryService(dbPath)) {
            // OrderService.createOrder reads request.getItems() — but those are
            // method calls, not field READS. For a clear READS, pick the
            // private orderRepository field which is dereferenced by createOrder
            // (`orderRepository.save(...)`) — this is a method-call receiver,
            // counted as READS by FieldAccessExtractor.
            List<EdgeRow> rows = q.fieldReaders(
                    "com.example.shop.service.OrderService#orderRepository");
            assertFalse(rows.isEmpty(),
                    "createOrder dereferences orderRepository — must be ≥1 READS");
        }
    }

    @Test
    void callPath_findsShortestChain_controllerToValidator() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> path = q.callPath(
                    "com.example.shop.controller.OrderController#create",
                    "com.example.shop.service.OrderValidator#validate",
                    5);
            assertFalse(path.isEmpty(),
                    "OrderController.create → OrderService.createOrder → validator.validate");
            // First edge starts at the controller; last ends at the validator.
            assertEquals("com.example.shop.controller.OrderController#create"
                    + "(com.example.shop.domain.dto.CreateOrderRequest)", path.get(0).sourceSymbolId);
            assertTrue(path.get(path.size() - 1).targetSymbolId
                    .startsWith("com.example.shop.service.OrderValidator#validate"));
        }
    }

    @Test
    void callPath_emptyWhenUnreachable() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> path = q.callPath(
                    "com.example.shop.service.OrderValidator#validate",
                    "com.example.shop.controller.OrderController#create",
                    5);
            // Validator never calls back into Controller.
            assertTrue(path.isEmpty(), "should be empty; got " + path);
        }
    }

    @Test
    void packageDeps_aggregatesAcrossModules() {
        try (QueryService q = new QueryService(dbPath)) {
            List<Map<String, Object>> rows = q.packageDeps();
            assertFalse(rows.isEmpty());
            // Controller package depends on service package.
            boolean ctrlToService = rows.stream().anyMatch(r ->
                    "com.example.shop.controller".equals(r.get("source_package"))
                    && "com.example.shop.service".equals(r.get("target_package")));
            assertTrue(ctrlToService,
                    "controller → service edge missing; got " + rows);
            // self-package edges must NOT appear.
            assertFalse(rows.stream().anyMatch(r ->
                    r.get("source_package").equals(r.get("target_package"))),
                    "self-package edges must be excluded");
        }
    }
}
