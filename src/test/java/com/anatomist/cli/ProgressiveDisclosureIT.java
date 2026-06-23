package com.anatomist.cli;

import com.anatomist.json.Json;
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

class ProgressiveDisclosureIT {

    @Test
    void surveyBaselineJson_returnsBoundedAgentMap(@TempDir Path tmp) throws Exception {
        Path db = buildFixtureIndex(tmp);

        RunResult r = runCli("survey-baseline", fixture().toString(),
                "--format", "json", "--index", db.toString());

        assertEquals(0, r.exitCode, r.stderr);
        Map<?, ?> json = asObject(r.stdout);
        assertEquals("survey-baseline", json.get("command"));
        assertEquals("ok", json.get("status"));
        assertNotNull(json.get("schema_version"));
        assertNotNull(json.get("overview"));
        assertNotNull(json.get("entry_candidates"));
        assertNotNull(json.get("domain_candidates"));
        assertNotNull(json.get("repositories"));
        assertNotNull(json.get("events"));
        assertNotNull(json.get("budget"));
        assertNotNull(json.get("candidate_sources"));
        assertNotNull(json.get("warnings"));
        assertFalse(((List<?>) json.get("next_queries")).isEmpty());
    }

    @Test
    void searchJson_supportsOffsetAndNextOffset(@TempDir Path tmp) throws Exception {
        Path db = buildFixtureIndex(tmp);

        RunResult r = runCli("search", "Order", "--limit", "1", "--offset", "1", "--index", db.toString());

        assertEquals(0, r.exitCode, r.stderr);
        Map<?, ?> json = asObject(r.stdout);
        assertEquals(1, ((List<?>) json.get("results")).size());
        Map<?, ?> stats = (Map<?, ?>) json.get("stats");
        assertEquals(1, ((Number) stats.get("offset")).intValue());
        assertTrue(((Number) stats.get("total")).intValue() > 1);
        assertEquals(Boolean.TRUE, stats.get("truncated"));
        assertEquals(2, ((Number) stats.get("next_offset")).intValue());
    }

    @Test
    void searchJson_firstPageStillReportsPaging(@TempDir Path tmp) throws Exception {
        Path db = buildFixtureIndex(tmp);

        RunResult r = runCli("search", "Order", "--limit", "1", "--index", db.toString());

        assertEquals(0, r.exitCode, r.stderr);
        Map<?, ?> json = asObject(r.stdout);
        Map<?, ?> stats = (Map<?, ?>) json.get("stats");
        assertEquals(0, ((Number) stats.get("offset")).intValue());
        assertEquals(1, ((Number) stats.get("limit")).intValue());
        assertTrue(((Number) stats.get("total")).intValue() > 1);
        assertEquals(Boolean.TRUE, stats.get("truncated"));
        assertEquals(1, ((Number) stats.get("next_offset")).intValue());
        assertNotNull(json.get("next_queries"));
    }

    @Test
    void contextJson_supportsMemberPaging(@TempDir Path tmp) throws Exception {
        Path db = buildFixtureIndex(tmp);

        RunResult r = runCli("context", "OrderService",
                "--members-limit", "2",
                "--members-offset", "1",
                "--index", db.toString());

        assertEquals(0, r.exitCode, r.stderr);
        Map<?, ?> json = asObject(r.stdout);
        Map<?, ?> result = (Map<?, ?>) ((List<?>) json.get("results")).get(0);
        assertEquals(2, ((List<?>) result.get("members")).size());
        Map<?, ?> stats = (Map<?, ?>) json.get("stats");
        assertEquals(1, ((Number) stats.get("members_offset")).intValue());
        assertTrue(((Number) stats.get("members_total")).intValue() > 2);
        assertEquals(Boolean.TRUE, stats.get("members_truncated"));
        assertNotNull(json.get("next_queries"));
    }

    @Test
    void callChainJson_supportsPagingFilterAndBudget(@TempDir Path tmp) throws Exception {
        Path db = buildFixtureIndex(tmp);

        RunResult r = runCli("callees-of", "OrderService#createOrder",
                "--depth", "3",
                "--filter", "Order",
                "--limit", "2",
                "--offset", "0",
                "--index", db.toString());

        assertEquals(0, r.exitCode, r.stderr);
        Map<?, ?> json = asObject(r.stdout);
        assertTrue(((List<?>) json.get("results")).size() <= 2);
        Map<?, ?> stats = (Map<?, ?>) json.get("stats");
        assertEquals(0, ((Number) stats.get("offset")).intValue());
        assertNotNull(stats.get("total"));
        assertNotNull(stats.get("truncated"));
        assertNotNull(json.get("budget"));
        assertNotNull(json.get("next_queries"));
    }

    @Test
    void callChainJson_defaultPageStillReportsBudget(@TempDir Path tmp) throws Exception {
        Path db = buildFixtureIndex(tmp);

        RunResult r = runCli("callees-of", "OrderService#createOrder",
                "--depth", "3",
                "--index", db.toString());

        assertEquals(0, r.exitCode, r.stderr);
        Map<?, ?> json = asObject(r.stdout);
        Map<?, ?> stats = (Map<?, ?>) json.get("stats");
        assertNotNull(stats.get("offset"));
        assertNotNull(stats.get("limit"));
        assertNotNull(stats.get("truncated"));
        assertNotNull(json.get("budget"));
    }

    @Test
    void contextJson_reportsAmbiguousTargetInsteadOfPickingFirst(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("ambiguous");
        Path src = project.resolve("src/main/java");
        Files.createDirectories(src.resolve("a"));
        Files.createDirectories(src.resolve("b"));
        Files.writeString(src.resolve("a/Duplicate.java"),
                "package a; public class Duplicate { public void one() {} }\n",
                StandardCharsets.UTF_8);
        Files.writeString(src.resolve("b/Duplicate.java"),
                "package b; public class Duplicate { public void two() {} }\n",
                StandardCharsets.UTF_8);

        Path db = tmp.resolve("ambiguous.db");
        RunResult index = runCli("index", project.toString(),
                "--project-source", src.toString(),
                "--no-classpath",
                "--output", db.toString());
        assertEquals(0, index.exitCode, index.stderr);

        RunResult r = runCli("context", "Duplicate", "--index", db.toString());

        assertEquals(2, r.exitCode, r.stderr);
        Map<?, ?> json = asObject(r.stdout);
        Map<?, ?> stats = (Map<?, ?>) json.get("stats");
        assertEquals(Boolean.TRUE, stats.get("ambiguous"));
        assertEquals(2, ((Number) stats.get("candidates")).intValue());
        assertEquals(2, ((List<?>) json.get("results")).size());
        assertNotNull(json.get("next_queries"));
    }

    @Test
    void helpMentionsProgressiveDisclosureCommandsAndOptions() {
        RunResult root = runCli("--help");
        assertEquals(0, root.exitCode, root.stderr);
        assertTrue(root.stdout.contains("survey-baseline"));

        RunResult search = runCli("search", "--help");
        assertEquals(0, search.exitCode, search.stderr);
        assertTrue(search.stdout.contains("--offset"));

        RunResult context = runCli("context", "--help");
        assertEquals(0, context.exitCode, context.stderr);
        assertTrue(context.stdout.contains("--members-limit"));

        RunResult callees = runCli("callees-of", "--help");
        assertEquals(0, callees.exitCode, callees.stderr);
        assertTrue(callees.stdout.contains("--filter"));
    }

    private static Path buildFixtureIndex(Path tmp) throws Exception {
        Path fixture = fixture();
        Path db = tmp.resolve("progressive.db");
        RunResult r = runCli("index", fixture.toString(),
                "--project-source", projectSource(fixture),
                "--no-classpath",
                "--output", db.toString());
        assertEquals(0, r.exitCode, r.stderr);
        assertTrue(Files.isRegularFile(db));
        return db;
    }

    private static Path fixture() {
        Path fixture = Path.of(System.getProperty("user.dir")).resolve("fixtures/mini-spring-shop");
        assertTrue(Files.isDirectory(fixture));
        return fixture;
    }

    private static String projectSource(Path fixture) {
        return String.join(File.pathSeparator,
                fixture.resolve("api/src/main/java").toString(),
                fixture.resolve("domain/src/main/java").toString(),
                fixture.resolve("service/src/main/java").toString());
    }

    private static Map<?, ?> asObject(String json) {
        Object tree = Json.parseTree(json);
        assertTrue(tree instanceof Map, "expected JSON object, got: " + json);
        return (Map<?, ?>) tree;
    }

    private static RunResult runCli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int rc = new CommandLine(new AnatomistCli()).execute(args);
            return new RunResult(rc, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private record RunResult(int exitCode, String stdout, String stderr) {}
}
