package com.anatomist.cli;

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

import static org.junit.jupiter.api.Assertions.*;

/** L2 IT for {@code export --format html} against {@code fixtures/mini-spring-shop}. */
class ExportCommandIT {

    static Path dbPath;

    @BeforeAll
    static void buildIndex(@TempDir Path tmp) throws Exception {
        Path repo = Path.of(System.getProperty("user.dir"));
        Path fixture = repo.resolve("fixtures/mini-spring-shop");
        assertTrue(Files.isDirectory(fixture));
        dbPath = tmp.resolve("export-it.db");

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
            assertEquals(0, cmd.call(), "index failed: " + cap.toString(StandardCharsets.UTF_8));
        } finally { System.setOut(old); }
    }

    @Test
    void export_html_writesSelfContainedFile(@TempDir Path out) throws Exception {
        Path html = out.resolve("shop.html");
        ExportCommand cmd = new ExportCommand();
        new CommandLine(cmd).parseArgs(
                "--index", dbPath.toString(),
                "--output", html.toString());
        assertEquals(0, runSilently(cmd));

        assertTrue(Files.exists(html));
        String content = Files.readString(html, StandardCharsets.UTF_8);
        assertTrue(content.contains("<html"), "self-contained HTML");
        assertFalse(content.contains("/*__ANATOMIST_DATA__*/"), "placeholder replaced");
        assertTrue(content.contains("com.example.shop.service"), "service package embedded");
        assertTrue(content.contains("OrderService"), "a class label embedded");
        assertTrue(content.contains("\"package_deps\""), "package deps embedded");
    }

    @Test
    void export_maxEdges_capsClassDeps(@TempDir Path out) throws Exception {
        Path html = out.resolve("capped.html");
        ExportCommand cmd = new ExportCommand();
        new CommandLine(cmd).parseArgs(
                "--index", dbPath.toString(),
                "--output", html.toString(),
                "--max-edges", "1");
        assertEquals(0, runSilently(cmd));
        String content = Files.readString(html, StandardCharsets.UTF_8);
        // class_deps array should hold at most one element → no comma between objects.
        int start = content.indexOf("\"class_deps\":[");
        assertTrue(start >= 0);
        String arr = content.substring(start + "\"class_deps\":[".length());
        arr = arr.substring(0, arr.indexOf("]"));
        long objs = arr.isEmpty() ? 0 : arr.chars().filter(c -> c == '{').count();
        assertTrue(objs <= 1, "max-edges=1 should cap class deps; got " + objs);
    }

    private static int runSilently(ExportCommand cmd) {
        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        PrintStream old = System.out;
        try {
            System.setOut(new PrintStream(cap, true, StandardCharsets.UTF_8));
            return cmd.call();
        } finally { System.setOut(old); }
    }
}
