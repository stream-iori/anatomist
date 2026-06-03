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

/** L2 IT for the {@code overview} command against {@code fixtures/mini-spring-shop}. */
class OverviewCommandIT {

    static Path dbPath;

    @BeforeAll
    static void buildIndex(@TempDir Path tmp) throws Exception {
        Path repo = Path.of(System.getProperty("user.dir"));
        Path fixture = repo.resolve("fixtures/mini-spring-shop");
        assertTrue(Files.isDirectory(fixture));
        dbPath = tmp.resolve("overview-it.db");

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

    private static String runOverview(String... extraArgs) {
        OverviewCommand cmd = new OverviewCommand();
        String[] base = { "--index", dbPath.toString() };
        String[] args = new String[base.length + extraArgs.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extraArgs, 0, args, base.length, extraArgs.length);
        new CommandLine(cmd).parseArgs(args);

        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        PrintStream old = System.out;
        try {
            System.setOut(new PrintStream(cap, true, StandardCharsets.UTF_8));
            assertEquals(0, cmd.call());
        } finally { System.setOut(old); }
        return cap.toString(StandardCharsets.UTF_8);
    }

    @Test
    void overview_json_hasKindsPackagesAndDeps() {
        String out = runOverview("--format", "json");
        assertTrue(out.contains("\"kind_counts\""), out);
        assertTrue(out.contains("\"CLASS\""), out);
        assertTrue(out.contains("\"packages\""));
        assertTrue(out.contains("com.example.shop.service"), "expected a service package name");
        assertTrue(out.contains("\"package_deps\""));
    }

    @Test
    void overview_markdown_rendersTables() {
        String out = runOverview("--format", "markdown");
        assertTrue(out.contains("# Project Overview"));
        assertTrue(out.contains("## Node Kinds"));
        assertTrue(out.contains("## Packages"));
    }

    @Test
    void overview_depthCollapsesPackages() {
        String full = runOverview("--format", "json");
        String collapsed = runOverview("--format", "json", "--depth", "2");
        // Collapsing to 2 segments folds com.example.* into "com.example".
        assertTrue(collapsed.contains("\"com.example\""), collapsed);
        assertFalse(full.equals(collapsed));
    }
}
