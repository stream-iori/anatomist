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

class AgentContractIT {

    @Test
    void everySubcommand_acceptsHelp() {
        String[] commands = {
                "index", "index-docs", "watch", "search", "context", "callees-of",
                "callers-of", "hierarchy", "implementors-of", "deps-of", "used-by",
                "field-access", "call-path", "overview", "export", "annotate", "lint",
                "doctor"
        };
        for (String cmd : commands) {
            RunResult r = runCli(cmd, "--help");
            assertEquals(0, r.exitCode, cmd + " --help should exit 0; stderr=" + r.stderr);
            assertTrue(r.stdout.contains("Usage:"), cmd + " --help should print usage");
        }
    }

    @Test
    void doctorJson_reportsIndexAndCapabilities(@TempDir Path tmp) throws Exception {
        Path db = buildFixtureIndex(tmp, false);

        RunResult r = runCli("doctor", "--format", "json", "--index", db.toString());
        assertEquals(0, r.exitCode, r.stderr);
        Map<?, ?> json = asObject(r.stdout);
        assertEquals("doctor", json.get("command"));
        assertEquals("ok", json.get("status"));
        assertEquals(Boolean.TRUE, json.get("index_exists"));
        assertTrue(((List<?>) json.get("commands")).contains("search"));
        assertNotNull(json.get("schema_version"));
        assertNotNull(json.get("default_index_path"));
    }

    @Test
    void indexJson_reportsStableSummary(@TempDir Path tmp) throws Exception {
        Path fixture = fixture();
        Path db = tmp.resolve("index-json.db");
        String projectSource = projectSource(fixture);

        RunResult r = runCli("index", fixture.toString(),
                "--project-source", projectSource,
                "--no-classpath",
                "--output", db.toString(),
                "--format", "json");
        assertEquals(0, r.exitCode, r.stderr);
        Map<?, ?> json = asObject(r.stdout);
        assertEquals("index", json.get("command"));
        assertEquals("ok", json.get("status"));
        assertEquals(db.toAbsolutePath().normalize().toString(), json.get("index_path"));
        Map<?, ?> stats = (Map<?, ?>) json.get("stats");
        assertTrue(((Number) stats.get("source_files")).intValue() >= 15);
        assertTrue(((Number) stats.get("classes")).intValue() >= 13);
        assertTrue(((Number) stats.get("methods")).intValue() >= 47);
        assertNotNull(stats.get("unresolved"));
        assertNotNull(json.get("schema_version"));
    }

    @Test
    void annotateAutoJson_reportsRoleQuality(@TempDir Path tmp) throws Exception {
        Path db = buildFixtureIndex(tmp, false);
        RunResult r = runCli("annotate", "--auto", "--format", "json", "--index", db.toString());
        assertEquals(0, r.exitCode, r.stderr);
        Map<?, ?> json = asObject(r.stdout);
        assertEquals("annotate --auto", json.get("command"));
        assertEquals("ok", json.get("status"));
        assertTrue(json.containsKey("roles_by_type"));
        assertTrue(json.containsKey("unclassified_count"));
        assertTrue(json.containsKey("reasons"));
    }

    @Test
    void searchByRoleEmptyJson_explainsReason(@TempDir Path tmp) throws Exception {
        Path db = buildFixtureIndex(tmp, false);
        RunResult r = runCli("search", "APPLICATION", "--by-role", "--index", db.toString());
        assertEquals(0, r.exitCode, r.stderr);
        Map<?, ?> json = asObject(r.stdout);
        Map<?, ?> stats = (Map<?, ?>) json.get("stats");
        assertEquals(0, ((Number) stats.get("total")).intValue());
        assertNotNull(stats.get("reason"));
        assertNotNull(stats.get("suggestions"));
    }

    private static Path buildFixtureIndex(Path tmp, boolean springXml) throws Exception {
        Path fixture = fixture();
        Path db = tmp.resolve("agent-contract.db");
        java.util.ArrayList<String> args = new java.util.ArrayList<>(List.of(
                "index", fixture.toString(),
                "--project-source", projectSource(fixture),
                "--no-classpath",
                "--output", db.toString()));
        if (springXml) args.add("--spring-xml");
        RunResult r = runCli(args.toArray(String[]::new));
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
