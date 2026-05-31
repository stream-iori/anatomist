package com.anatomist.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class IndexDocsCommandIT {

    @Test
    void scansReadme_titleFromH1(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("proj");
        Files.createDirectories(project);
        Files.writeString(project.resolve("README.md"),
                "# Mini Spring Shop\n\nA demo project.\n",
                StandardCharsets.UTF_8);

        Path db = run(project, tmp.resolve("index.db"));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT path, title, doc_type, module FROM documents WHERE path='README.md'")) {
                assertTrue(rs.next(), "README.md row missing");
                assertEquals("README.md", rs.getString("path"));
                assertEquals("Mini Spring Shop", rs.getString("title"));
                assertEquals("README", rs.getString("doc_type"));
                assertNull(rs.getString("module"));
            }
            // FTS5 sync
            assertEquals(1, scalar(st, "SELECT count(*) FROM doc_content WHERE doc_content MATCH 'demo'"));
        }
    }

    @Test
    void scansAdr_titleFallsBackToStem(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("proj");
        Files.createDirectories(project.resolve("docs"));
        Files.writeString(project.resolve("docs/ADR-001-use-cqrs.md"),
                "We chose CQRS for the read side.\n",
                StandardCharsets.UTF_8);

        Path db = run(project, tmp.resolve("index.db"));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT path, title, doc_type FROM documents WHERE path LIKE '%ADR-001%'")) {
                assertTrue(rs.next(), "ADR row missing");
                assertEquals("docs/ADR-001-use-cqrs.md", rs.getString("path"));
                assertEquals("ADR-001-use-cqrs", rs.getString("title"));
                assertEquals("ADR", rs.getString("doc_type"));
            }
        }
    }

    @Test
    void scansMultiModule_inferringModule(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("proj");
        Files.createDirectories(project.resolve("domain/docs"));
        Files.writeString(project.resolve("domain/docs/order-model.md"),
                "# Order model\n", StandardCharsets.UTF_8);

        Path db = run(project, tmp.resolve("index.db"));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT module FROM documents WHERE path LIKE '%order-model%'")) {
                assertTrue(rs.next(), "module row missing");
                assertEquals("domain", rs.getString("module"));
            }
        }
    }

    @Test
    void excludesChangelogAndSwagger(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("proj");
        Files.createDirectories(project);
        Files.writeString(project.resolve("CHANGELOG.md"), "# Changes\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("swagger.json"), "{}\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("openapi.json"), "{}\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("README.md"), "# Keep me\n", StandardCharsets.UTF_8);

        Path db = run(project, tmp.resolve("index.db"));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(0, scalar(st, "SELECT count(*) FROM documents WHERE path LIKE '%CHANGELOG%'"));
            assertEquals(0, scalar(st, "SELECT count(*) FROM documents WHERE path LIKE '%swagger%'"));
            assertEquals(0, scalar(st, "SELECT count(*) FROM documents WHERE path LIKE '%openapi%'"));
            assertEquals(1, scalar(st, "SELECT count(*) FROM documents WHERE path='README.md'"));
        }
    }

    private static Path run(Path project, Path db) {
        IndexDocsCommand cmd = new IndexDocsCommand();
        new CommandLine(cmd).parseArgs(project.toString(), "--output", db.toString());
        assertEquals(0, cmd.call(), "index-docs should exit 0");
        return db;
    }

    private static int scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
