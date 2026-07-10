package com.anatomist.skill;

import com.anatomist.cli.IndexCommand;
import com.anatomist.cli.IndexDocsCommand;
import com.anatomist.query.ContextResult;
import com.anatomist.query.EdgeRow;
import com.anatomist.query.EnrichResult;
import com.anatomist.query.HierarchyResult;
import com.anatomist.query.NodeRow;
import com.anatomist.query.QueryService;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillScenarioCoverageIT {

    static Path dbPath;

    @BeforeAll
    static void buildSkillCoverageIndex(@TempDir Path tmp) throws Exception {
        Path repo = Path.of(System.getProperty("user.dir"));
        Path fixture = repo.resolve("fixtures/mini-spring-shop");
        assertTrue(Files.isDirectory(fixture));

        dbPath = tmp.resolve("skill-coverage.db");
        String projectSource = String.join(File.pathSeparator,
                "api/src/main/java",
                "domain/src/main/java",
                "service/src/main/java");

        runQuietly(() -> new CommandLine(new IndexCommand()).execute(
                fixture.toString(),
                "--project-source", projectSource,
                "--no-classpath",
                "--spring-xml",
                "--output", dbPath.toString()));

        runQuietly(() -> new CommandLine(new IndexDocsCommand()).execute(
                fixture.toString(),
                "--index", dbPath.toString()));

        assertTrue(Files.exists(dbPath));
    }

    @Test
    void entryDiscoveryAndLocalContextAreCovered() {
        try (QueryService q = new QueryService(dbPath)) {
            List<NodeRow> routes = q.searchByName("*", "ROUTE", 20);
            assertTrue(routes.stream().anyMatch(r -> "POST /api/orders".equals(r.label)));
            assertTrue(routes.stream().anyMatch(r -> "GET /api/orders/{id}".equals(r.label)));

            ContextResult context = q.context("com.example.shop.service.OrderService", 0);
            assertEquals("OrderService", context.node.label);
            assertTrue(context.members.stream().anyMatch(m -> "orderRepository".equals(m.label)));
            assertTrue(context.members.stream().anyMatch(m -> "createOrder".equals(m.label)));
        }
    }

    @Test
    void forwardReverseAndCallPathScenariosAreCovered() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> forward = q.calleesOf(
                    "com.example.shop.controller.OrderController#create", 8, true);
            assertTrue(forward.stream().anyMatch(e ->
                    e.target != null && e.target.contains("OrderService#createOrder")));
            assertTrue(forward.stream().anyMatch(e ->
                    e.target != null && e.target.contains("OrderRepository#save")));

            List<EdgeRow> reverse = q.callersOf(
                    "com.example.shop.repository.OrderRepository#save", 8, true);
            assertTrue(reverse.stream().anyMatch(e ->
                    e.source != null && e.source.contains("OrderService#createOrder")));
            assertTrue(reverse.stream().anyMatch(e ->
                    e.source != null && e.source.contains("OrderController#create")));

            List<EdgeRow> path = q.callPath(
                    "com.example.shop.controller.OrderController#create",
                    "com.example.shop.repository.OrderRepository#save",
                    8);
            assertEquals(2, path.size(), "Controller -> Service -> Repository path should be focused");
        }
    }

    @Test
    void typeAndFieldRelationScenariosAreCovered() {
        try (QueryService q = new QueryService(dbPath)) {
            HierarchyResult hierarchy = q.hierarchy("com.example.shop.service.OrderService");
            assertTrue(hierarchy.extendsChain.stream().anyMatch(e ->
                    "com.example.shop.service.BaseService".equals(e.qualifiedName)));

            List<NodeRow> subtypes = q.implementorsOf(
                    "com.example.shop.repository.OrderRepository", true);
            assertTrue(subtypes.stream().anyMatch(n ->
                    "com.example.shop.repository.InMemoryOrderRepository".equals(n.qualifiedName)));
            assertTrue(subtypes.stream().anyMatch(n ->
                    "com.example.shop.repository.AuditedOrderRepository".equals(n.qualifiedName)));

            List<EdgeRow> usedByOrder = q.usedBy("com.example.shop.domain.entity.Order");
            assertTrue(usedByOrder.stream().anyMatch(e ->
                    "REFERENCES".equals(e.relation)
                            && "field_type".equals(e.context)
                            && "com.example.shop.domain.event.OrderCreatedEvent#order"
                                    .equals(e.sourceSymbolId)));

            List<EdgeRow> statusAccess = q.fieldAccessPaged(
                    "com.example.shop.domain.entity.Order#status", "all", 50, 0, null).items();
            assertTrue(statusAccess.stream().anyMatch(e -> "READS".equals(e.relation)));
            assertTrue(statusAccess.stream().anyMatch(e -> "WRITES".equals(e.relation)));
        }
    }

    @Test
    void architectureFrameworkDocsAndEvidenceHygieneAreCovered() throws Exception {
        try (QueryService q = new QueryService(dbPath)) {
            List<Map<String, Object>> deps = q.packageDeps();
            assertTrue(deps.stream().anyMatch(row ->
                    "com.example.shop.controller".equals(row.get("source_package"))
                            && "com.example.shop.service".equals(row.get("target_package"))
                            && "CALLS".equals(row.get("relation"))));

            List<EdgeRow> priceUsers = q.usedBy("com.example.shop.service.PriceCalculator");
            assertTrue(priceUsers.stream().anyMatch(e -> "INJECTS".equals(e.relation)));
            assertTrue(priceUsers.stream().anyMatch(e -> "WIRES".equals(e.relation)));

            EnrichResult enriched = q.enrichNode("OrderService", 1, true);
            assertFalse(enriched.relatedDocs.isEmpty(), "README docs should enrich OrderService");
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = c.createStatement()) {
            assertTrue(metaExists(st, "source_root"));
            assertTrue(metaExists(st, "indexed_at"));
            assertTrue(metaExists(st, "source_git_commit"));
            assertTrue(metaExists(st, "source_git_dirty"));
        }
    }

    private static boolean metaExists(Statement st, String key) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT value FROM project_meta WHERE key='" + key + "'")) {
            return rs.next() && rs.getString(1) != null;
        }
    }

    private static void runQuietly(CheckedIntSupplier action) throws Exception {
        PrintStream old = System.out;
        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(cap, true, StandardCharsets.UTF_8));
            int rc = action.getAsInt();
            assertEquals(0, rc, cap.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(old);
        }
    }

    @FunctionalInterface
    interface CheckedIntSupplier {
        int getAsInt() throws Exception;
    }
}
