package com.anatomist.cli;

import com.anatomist.model.Document;
import com.anatomist.model.SemanticAnnotation;
import com.anatomist.store.SqliteStore;
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

/** L3 end-to-end test for {@link EnrichCommand} via the picocli CLI on
 *  {@code fixtures/mini-spring-shop}. */
class EnrichCommandIT {

    static Path dbPath;

    @BeforeAll
    static void buildIndex(@TempDir Path tmp) throws Exception {
        Path repo = Path.of(System.getProperty("user.dir"));
        Path fixture = repo.resolve("fixtures/mini-spring-shop");
        assertTrue(Files.isDirectory(fixture));

        dbPath = tmp.resolve("enrich-cli.db");

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
            assertEquals(0, cmd.call(), cap.toString(StandardCharsets.UTF_8));
        } finally { System.setOut(old); }

        try (SqliteStore store = new SqliteStore(dbPath)) {
            Document d = new Document();
            d.path = "docs/order-service.md";
            d.title = "OrderService overview";
            d.content = "The OrderService handles order creation.";
            d.docType = "ADR";
            store.insertDocuments(List.of(d));

            SemanticAnnotation sa = new SemanticAnnotation();
            sa.nodeId = "com.example.shop.service.OrderService";
            sa.category = "BUSINESS_SERVICE";
            sa.businessLabel = "订单服务";
            sa.source = "LLM";
            sa.confidence = "MEDIUM";
            store.upsertSemanticAnnotation(sa);
        }
    }

    @Test
    void enrich_node_markdownDefault() {
        String out = runCli("enrich", "--node", "OrderService",
                "--index", dbPath.toString());
        assertTrue(out.contains("# OrderService"));
        assertTrue(out.contains("## Semantic Annotations"));
        assertTrue(out.contains("订单服务"));
        long lines = out.lines().count();
        assertTrue(lines <= MarkdownFormatter_LINE_CAP + 10,
                "enrich --node OrderService should stay under cap; got " + lines);
    }

    @Test
    void enrich_node_json() {
        String out = runCli("enrich", "--node", "OrderService",
                "--format", "json",
                "--index", dbPath.toString());
        assertTrue(out.contains("\"query\""));
        assertTrue(out.contains("\"results\""));
        assertTrue(out.contains("OrderService"));
    }

    @Test
    void enrich_package_markdown() {
        String out = runCli("enrich", "--package", "com.example.shop.service",
                "--index", dbPath.toString());
        assertTrue(out.contains("# com.example.shop.service"));
        assertTrue(out.contains("OrderService"));
    }

    @Test
    void enrich_node_withDocs_includesSnippet() {
        String out = runCli("enrich", "--node", "OrderService", "--with-docs",
                "--index", dbPath.toString());
        assertTrue(out.contains("Related Documentation") || out.contains("OrderService overview"),
                "expected related documentation section; got:\n" + out);
    }

    @Test
    void enrich_missingTarget_exit2() {
        int rc = runCliExit("enrich", "--node", "com.does.not.Exist",
                "--index", dbPath.toString());
        assertEquals(2, rc);
    }

    // The cap is taken from MarkdownFormatter.MAX_LINES; duplicate constant here
    // so the test failure surfaces a clearer assertion message.
    static final int MarkdownFormatter_LINE_CAP =
            com.anatomist.query.MarkdownFormatter.MAX_LINES;

    private static String runCli(String... args) {
        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        PrintStream old = System.out;
        try {
            System.setOut(new PrintStream(cap, true, StandardCharsets.UTF_8));
            int rc = new CommandLine(new AnatomistCli()).execute(args);
            assertEquals(0, rc, "expected 0; got " + rc + "; stdout:\n" + cap.toString(StandardCharsets.UTF_8));
        } finally { System.setOut(old); }
        return cap.toString(StandardCharsets.UTF_8);
    }

    private static int runCliExit(String... args) {
        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        PrintStream old = System.out;
        try {
            System.setOut(new PrintStream(cap, true, StandardCharsets.UTF_8));
            return new CommandLine(new AnatomistCli()).execute(args);
        } finally { System.setOut(old); }
    }
}
