package com.anatomist.cli;

import com.anatomist.test.CliTestSupport;
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

    @Test
    void indexAliasWorksOnExistingSchemaAndReplacesDocs(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("proj");
        Files.createDirectories(project);
        Files.writeString(project.resolve("README.md"),
                "# First\n\none\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("A.java"),
                "package p; class A {}\n", StandardCharsets.UTF_8);

        Path db = tmp.resolve("index.db");
        assertEquals(0, new CommandLine(new IndexCommand()).execute(
                project.toString(), "--format", "json", "--no-classpath", "--output", db.toString()));

        assertEquals(0, new CommandLine(new IndexDocsCommand()).execute(
                project.toString(), "--index", db.toString()));

        Files.writeString(project.resolve("README.md"),
                "# Second\n\ntwo\n", StandardCharsets.UTF_8);
        assertEquals(0, new CommandLine(new IndexDocsCommand()).execute(
                project.toString(), "--index", db.toString()));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(1, scalar(st, "SELECT count(*) FROM documents WHERE path='README.md'"));
            try (ResultSet rs = st.executeQuery("SELECT title FROM documents WHERE path='README.md'")) {
                assertTrue(rs.next());
                assertEquals("Second", rs.getString(1));
            }
        }
    }

    @Test
    void rejectsDifferentProjectWithoutChangingDocumentsOrIdentity(@TempDir Path tmp) throws Exception {
        Path first = Files.createDirectories(tmp.resolve("first"));
        Path second = Files.createDirectories(tmp.resolve("second"));
        Files.writeString(first.resolve("README.md"), "# First\n");
        Files.writeString(second.resolve("README.md"), "# Second\n");
        Path db = run(first, tmp.resolve("shared.db"));

        CliTestSupport.RunResult mismatch = CliTestSupport.capture(() ->
                new CommandLine(new IndexDocsCommand()).execute(
                        second.toString(), "--index", db.toString()));
        assertEquals(2, mismatch.exitCode(), mismatch.stdout() + mismatch.stderr());
        assertTrue(mismatch.stderr().contains("INDEX_PROJECT_MISMATCH"), mismatch.stderr());
        assertFalse(mismatch.stderr().contains("Exception"), mismatch.stderr());

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(first.toRealPath().toString(), scalarString(st,
                    "SELECT value FROM project_meta WHERE key='source_root'"));
            assertEquals("First", scalarString(st,
                    "SELECT title FROM documents WHERE path='README.md'"));
            assertEquals(1, scalar(st, "SELECT count(*) FROM documents"));
        }
    }

    @Test
    void canonicalSymlinkCanRefreshSameProject(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(project.resolve("README.md"), "# Before\n");
        Path db = run(project, tmp.resolve("symlink.db"));
        Path alias = tmp.resolve("alias");
        try {
            Files.createSymbolicLink(alias, project);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            return;
        }
        Files.writeString(project.resolve("README.md"), "# After\n");
        assertEquals(0, new CommandLine(new IndexDocsCommand()).execute(
                alias.toString(), "--index", db.toString()));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals("After", scalarString(st,
                    "SELECT title FROM documents WHERE path='README.md'"));
            assertEquals(project.toRealPath().toString(), scalarString(st,
                    "SELECT value FROM project_meta WHERE key='source_root'"));
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

    private static String scalarString(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }
}
