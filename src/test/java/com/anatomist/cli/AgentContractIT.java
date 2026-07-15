package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.test.CliTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentContractIT {

    @Test
    void everySubcommand_acceptsHelp() {
        String[] commands = {
                "index", "index-docs", "watch", "search", "context", "callees-of",
                "callers-of", "branches-of", "bean-config", "hierarchy", "implementors-of", "deps-of", "used-by",
                "field-access", "call-path", "overview", "survey-baseline", "export",
                "annotate", "doctor"
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
        assertTrue(((List<?>) json.get("commands")).contains("survey-baseline"));
        assertTrue(((List<?>) json.get("commands")).contains("branches-of"));
        assertTrue(((List<?>) json.get("commands")).contains("bean-config"));
        assertTrue(((List<?>) json.get("capabilities")).contains("branch-context-slices"));
        assertTrue(((List<?>) json.get("capabilities")).contains("spring-xml-config-tree"));
        assertTrue(((List<?>) json.get("capabilities")).contains("source-snapshot-fingerprint"));
        assertNotNull(json.get("schema_version"));
        assertNotNull(json.get("default_index_path"));
        assertEquals(fixture().toRealPath().toString(), json.get("source_root"));
        assertTrue(String.valueOf(json.get("source_snapshot_fingerprint"))
                .matches("sha256:[0-9a-f]{64}"));
        assertEquals("none", json.get("classpath_mode"));
        assertEquals(Boolean.FALSE, json.get("spring_xml"));
    }

    @Test
    void doctorReportsGitUntrackedCacheWithoutChangingIt(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        git(project, "init", "-q");
        git(project, "config", "user.name", "Anatomist Test");
        git(project, "config", "user.email", "anatomist@example.test");
        git(project, "config", "core.untrackedCache", "true");
        git(project, "add", ".");
        git(project, "commit", "-qm", "initial");
        Path db = tmp.resolve("doctor-git.db");
        RunResult indexed = runCli("index", project.toString(), "--no-classpath",
                "--output", db.toString());
        assertEquals(0, indexed.exitCode, indexed.stderr);

        RunResult enabled = runCli("doctor", "--format", "json", "--index", db.toString());
        assertEquals(0, enabled.exitCode, enabled.stderr);
        Map<?, ?> enabledJson = asObject(enabled.stdout);
        assertEquals("enabled", enabledJson.get("git_untracked_cache"));
        assertFalse(enabledJson.containsKey("advice"));

        git(project, "config", "core.untrackedCache", "false");
        RunResult disabled = runCli("doctor", "--format", "json", "--index", db.toString());
        Map<?, ?> disabledJson = asObject(disabled.stdout);
        assertEquals("disabled", disabledJson.get("git_untracked_cache"));
        assertTrue(String.valueOf(disabledJson.get("advice"))
                .contains("git config core.untrackedCache true"));
    }

    @Test
    void surveyBaselineRejectsIndexFromDifferentProject(@TempDir Path tmp) throws Exception {
        Path db = buildFixtureIndex(tmp, false);
        Path other = Files.createDirectories(tmp.resolve("other-project"));

        RunResult r = runCli("survey-baseline", other.toString(),
                "--format", "json", "--index", db.toString());

        assertEquals(2, r.exitCode, r.stderr);
        Map<?, ?> json = asObject(r.stdout);
        assertEquals("error", json.get("status"));
        assertEquals("INDEX_PROJECT_MISMATCH", json.get("error"));
        assertEquals(other.toRealPath().toString(), json.get("project_path"));
        assertEquals(fixture().toRealPath().toString(), json.get("index_source_root"));
    }

    @Test
    void strictHealthReturnsNonZeroForPersistedDegradedIndex(@TempDir Path tmp) throws Exception {
        Path db = buildFixtureIndex(tmp, false);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO index_diagnostics"
                    + "(severity,code,phase,occurrence_count,sample) VALUES "
                    + "('warning','DANGLING_FACTS_DROPPED','EDGE_BINDING',1,'test fixture')");
        }

        RunResult doctor = runCli("doctor", "--strict-health", "--format", "json",
                "--index", db.toString());
        assertEquals(3, doctor.exitCode, doctor.stderr);
        assertEquals("degraded", asObject(doctor.stdout).get("health"));

        RunResult survey = runCli("survey-baseline", "--strict-health", "--format", "json",
                "--index", db.toString());
        assertEquals(3, survey.exitCode, survey.stderr);
        assertEquals("degraded", asObject(survey.stdout).get("health"));
    }

    @Test
    void beanConfigJson_reportsSpringXmlConfigTree(@TempDir Path tmp) throws Exception {
        Path db = buildFixtureIndex(tmp, true);

        RunResult r = runCli("bean-config", "orderService",
                "--property", "eventPublisher",
                "--format", "json",
                "--index", db.toString());
        assertEquals(0, r.exitCode, r.stderr);
        Map<?, ?> json = asObject(r.stdout);
        List<?> results = (List<?>) json.get("results");
        assertFalse(results.isEmpty());
        Map<?, ?> bean = (Map<?, ?>) results.get(0);
        List<?> children = (List<?>) bean.get("children");
        assertEquals(1, children.size());
        Map<?, ?> property = (Map<?, ?>) children.get(0);
        assertEquals("property", property.get("xmlKind"));
        assertEquals("eventPublisher", property.get("name"));
        List<?> refs = (List<?>) property.get("children");
        assertEquals("ref", ((Map<?, ?>) refs.get(0)).get("xmlKind"));
        assertEquals("orderEventPublisher", ((Map<?, ?>) refs.get(0)).get("bean"));
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

    private static void git(Path cwd, String... args) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private record RunResult(int exitCode, String stdout, String stderr) {}
}
