package com.anatomist.query;

import com.anatomist.cli.IndexCommand;
import com.anatomist.store.SqliteStore;
import com.anatomist.model.Document;
import com.anatomist.model.SemanticAnnotation;
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

import static org.junit.jupiter.api.Assertions.*;

/** L2 integration test for {@link QueryService#enrichNode} /
 *  {@link QueryService#enrichPackage}. Drives index → docs index → enrich
 *  against {@code fixtures/mini-spring-shop}. */
class EnrichQueryIT {

    static Path dbPath;

    @BeforeAll
    static void buildIndex(@TempDir Path tmp) throws Exception {
        Path repo = Path.of(System.getProperty("user.dir"));
        Path fixture = repo.resolve("fixtures/mini-spring-shop");
        assertTrue(Files.isDirectory(fixture));

        dbPath = tmp.resolve("enrich-it.db");

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
        run(cmd);

        // Seed a Document referencing OrderService + one semantic annotation so
        // semanticAnnotations and (when --with-docs) relatedDocs have content.
        try (SqliteStore store = new SqliteStore(dbPath)) {
            Document d = new Document();
            d.path = "docs/order-service.md";
            d.title = "OrderService overview";
            d.content = "The OrderService handles order creation and payment.";
            d.docType = "ADR";
            store.insertDocuments(List.of(d));

            SemanticAnnotation sa = new SemanticAnnotation();
            sa.nodeId = "service::MAIN::com.example.shop.service.OrderService";
            sa.category = "REVIEWED";
            sa.businessLabel = "reviewed";
            sa.source = "LLM";
            sa.confidence = "MEDIUM";
            store.upsertSemanticAnnotation(sa);
        }
    }

    private static void run(IndexCommand cmd) throws Exception {
        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        PrintStream old = System.out;
        try {
            System.setOut(new PrintStream(cap, true, StandardCharsets.UTF_8));
            assertEquals(0, cmd.call(), "index failed: " + cap.toString(StandardCharsets.UTF_8));
        } finally { System.setOut(old); }
    }

    @Test
    void enrichNode_returnsAggregateView() {
        try (QueryService q = new QueryService(dbPath)) {
            EnrichResult r = q.enrichNode("com.example.shop.service.OrderService", 1, false);
            assertNotNull(r);
            assertNotNull(r.node);
            assertEquals("OrderService", r.node.label);
            assertFalse(r.members.isEmpty(), "OrderService has methods/fields");
            assertTrue(r.members.stream().anyMatch(m -> "createOrder".equals(m.label)));
            assertFalse(r.semanticAnnotations.isEmpty(), "seeded LLM annotation should be present");
            assertEquals("REVIEWED", r.semanticAnnotations.get(0).category);
            assertFalse(r.callees.isEmpty(), "createOrder calls should be picked up at depth=1");
            assertFalse(r.suggestedQueries.isEmpty(), "suggested queries should be derived");
        }
    }

    @Test
    void enrichNode_resolvesByShortLabel() {
        try (QueryService q = new QueryService(dbPath)) {
            EnrichResult r = q.enrichNode("OrderService", 1, false);
            assertNotNull(r);
            assertNotNull(r.node);
            assertEquals("com.example.shop.service.OrderService", r.node.qualifiedName);
        }
    }

    @Test
    void enrichNode_returnsNullForUnknown() {
        try (QueryService q = new QueryService(dbPath)) {
            EnrichResult r = q.enrichNode("com.does.not.Exist", 1, false);
            assertNull(r);
        }
    }

    @Test
    void enrichPackage_listsTypesInPackage() {
        try (QueryService q = new QueryService(dbPath)) {
            EnrichResult r = q.enrichPackage("com.example.shop.service", false);
            assertNotNull(r);
            assertEquals("com.example.shop.service", r.pkg);
            assertFalse(r.members.isEmpty());
            assertTrue(r.members.stream().anyMatch(m -> "OrderService".equals(m.label)));
            assertFalse(r.packageDeps.isEmpty(), "service package depends on other packages");
        }
    }

    @Test
    void enrichNode_withDocs_attachesSnippets() {
        try (QueryService q = new QueryService(dbPath)) {
            EnrichResult r = q.enrichNode("com.example.shop.service.OrderService", 1, true);
            assertNotNull(r);
            assertFalse(r.relatedDocs.isEmpty(), "seeded doc mentions OrderService");
            assertEquals("OrderService overview", r.relatedDocs.get(0).title);
        }
    }

    @Test
    void readSemanticAnnotations_returnsSeeded() {
        try (QueryService q = new QueryService(dbPath)) {
            List<SemanticAnnotationRow> rows = q.readSemanticAnnotations(
                    "com.example.shop.service.OrderService");
            // @BeforeAll seeds one explicit LLM row. The indexer must not add
            // convention-based business/category rows.
            assertTrue(rows.size() >= 1);
            assertTrue(rows.stream().anyMatch(r ->
                    "REVIEWED".equals(r.category) && "LLM".equals(r.source)));
            assertTrue(rows.stream().noneMatch(r -> "CONVENTION".equals(r.source)));
        }
    }

    @Test
    void suggestQueries_includesCallersOfForMethod() {
        try (QueryService q = new QueryService(dbPath)) {
            EnrichResult r = q.enrichNode(
                    "com.example.shop.service.OrderService#createOrder", 0, false);
            assertNotNull(r);
            assertNotNull(r.node);
            assertEquals("METHOD", r.node.kind);
            assertTrue(r.suggestedQueries.stream().anyMatch(s -> s.contains("callers-of")),
                    "method enrich should suggest callers-of; got " + r.suggestedQueries);
        }
    }
}
