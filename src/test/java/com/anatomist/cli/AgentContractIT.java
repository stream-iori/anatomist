package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.test.CliTestSupport;
import com.anatomist.store.SqliteStore;
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
                "skill", "index", "index-docs", "search", "context", "callees-of",
                "callers-of", "branches-of", "bean-config", "hierarchy", "implementors-of", "deps-of", "used-by",
                "field-access", "call-path", "overview", "survey-baseline",
                "annotate", "doctor"
        };
        for (String cmd : commands) {
            RunResult r = runCli(cmd, "--help");
            assertEquals(0, r.exitCode, cmd + " --help should exit 0; stderr=" + r.stderr);
            assertTrue(r.stdout.contains("Usage:"), cmd + " --help should print usage");
        }
    }

    @Test
    void exportIsNoLongerACliCommand() {
        RunResult r = runCli("export");
        assertEquals(2, r.exitCode);
        assertTrue((r.stdout + r.stderr).contains("Unmatched argument"));
    }

    @Test
    void watchIsNoLongerACliCommand() {
        RunResult r = runCli("watch");
        assertEquals(2, r.exitCode);
        assertTrue((r.stdout + r.stderr).contains("Unmatched argument"));
    }

    @Test
    void removedDataflowCommandsAndIndexOptionsFailParsing() {
        for (String command : List.of(
                "flow-materialize", "flow-of", "flow-path", "flow-summary",
                "guards-of", "exception-flow", "taint-path")) {
            RunResult result = runCli(command);
            assertEquals(2, result.exitCode, command);
        }
        for (String[] args : List.of(
                new String[]{"index", "--dataflow"},
                new String[]{"index", "--dataflow-mode", "full"},
                new String[]{"index", "--dataflow-scope", "package:p.**"},
                new String[]{"index", "--implicit-taint"},
                new String[]{"index", "--no-implicit-taint"})) {
            RunResult result = runCli(args);
            assertEquals(2, result.exitCode, String.join(" ", args));
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
        assertEquals("idle", json.get("freshness_state"));
        assertEquals(Boolean.TRUE, json.get("index_exists"));
        assertTrue(((List<?>) json.get("commands")).contains("search"));
        assertTrue(((List<?>) json.get("commands")).contains("skill"));
        assertTrue(((List<?>) json.get("commands")).contains("survey-baseline"));
        assertTrue(((List<?>) json.get("commands")).contains("branches-of"));
        assertFalse(((List<?>) json.get("commands")).contains("flow-materialize"));
        assertTrue(((List<?>) json.get("commands")).contains("bean-config"));
        assertFalse(((List<?>) json.get("commands")).contains("watch"));
        assertFalse(((List<?>) json.get("commands")).contains("export"));
        assertTrue(((List<?>) json.get("capabilities")).contains("branch-context-slices"));
        assertTrue(((List<?>) json.get("capabilities")).contains("spring-xml-config-tree"));
        assertTrue(((List<?>) json.get("capabilities")).contains("source-snapshot-fingerprint"));
        assertTrue(((List<?>) json.get("capabilities")).contains("context-source-view-v2"));
        assertTrue(((List<?>) json.get("capabilities")).contains("json-query-output-v2"));
        assertEquals(1, ((Number) json.get("graph_semantics_version")).intValue());
        assertTrue(((List<?>) json.get("capabilities")).contains("core-reflection"));
        assertFalse(((List<?>) json.get("capabilities")).contains("progressive-dataflow"));
        assertTrue(((List<?>) json.get("capabilities")).contains("file-resolution-coverage"));
        assertTrue(((List<?>) json.get("capabilities")).contains("agent-skill-topics"));
        assertNotNull(json.get("schema_version"));
        assertNotNull(json.get("default_index_path"));
        assertEquals(fixture().toRealPath().toString(), json.get("source_root"));
        assertTrue(String.valueOf(json.get("source_snapshot_fingerprint"))
                .matches("sha256:[0-9a-f]{64}"));
        assertEquals("none", json.get("classpath_mode"));
        assertEquals(Boolean.FALSE, json.get("spring_xml"));
        assertEquals("none", ((Map<?, ?>) json.get("classpath_detection")).get("origin"));
        assertTrue(((Map<?, ?>) json.get("source_snapshot")).containsKey("match"));
        assertNotNull(json.get("resolution_diagnostic_counts"));
        assertNotNull(json.get("resolution_diagnostic_groups"));
        assertNotNull(json.get("diagnostic_aggregation"));
        assertNotNull(json.get("diagnostic_storage"));
        assertFalse(json.containsKey("dataflow_mode"));
        assertFalse(json.containsKey("dataflow_scopes"));
    }

    @Test
    void doctorSeparatesTruncationMetadataAndReturnsFilteredFileCoverage(@TempDir Path tmp)
            throws Exception {
        Path db = buildFixtureIndex(tmp, false);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO index_diagnostics"
                    + "(severity,code,phase,occurrence_count,sample) VALUES "
                    + "('info','DIAGNOSTIC_LIMIT_REACHED','RESOLUTION',17,'limit')");
            statement.executeUpdate("INSERT INTO index_diagnostics"
                    + "(severity,code,phase,occurrence_count,sample) VALUES "
                    + "('info','DIAGNOSTIC_STORAGE_TRUNCATED','RESOLUTION',4,'storage')");
            statement.executeUpdate("INSERT OR REPLACE INTO analysis_coverage"
                    + "(source_file,module,scope,capability,status,occurrences,groups_count,"
                    + "codes,code_counts,details_truncated) VALUES "
                    + "('service/src/main/java/p/Target.java','service','MAIN','AGGREGATE',"
                    + "'partial',3,2,'[\"METHOD_NOT_FOUND\"]',"
                    + "'{\"METHOD_NOT_FOUND\":3}',0)");
        }

        RunResult result = runCli("doctor", "--format", "json", "--index", db.toString(),
                "--diagnostic-file", "p/Target.java");
        assertEquals(0, result.exitCode, result.stderr);
        Map<?, ?> json = asObject(result.stdout);
        assertEquals(Boolean.TRUE,
                ((Map<?, ?>) json.get("diagnostic_aggregation")).get("truncated"));
        assertEquals(17L,
                ((Number) ((Map<?, ?>) json.get("diagnostic_aggregation"))
                        .get("overflow_occurrences")).longValue());
        assertEquals(Boolean.TRUE,
                ((Map<?, ?>) json.get("diagnostic_storage")).get("truncated"));
        Map<?, ?> coverage = (Map<?, ?>) json.get("diagnostic_coverage");
        List<?> files = (List<?>) coverage.get("files");
        assertEquals(1, files.size());
        assertEquals("service/src/main/java/p/Target.java",
                ((Map<?, ?>) files.get(0)).get("source_file"));
        Map<?, ?> other = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) json
                .get("health_dimensions")).get("resolution")).get("other");
        assertFalse(String.valueOf(other.get("codes")).contains("DIAGNOSTIC_"));
    }

    @Test
    void doctorAgentPreflightIsReadOnlyAndSuppliesAgentContract(@TempDir Path tmp) throws Exception {
        Path db = buildFixtureIndex(tmp, false);
        long bytesBefore = Files.size(db);
        byte[] contentBefore = Files.readAllBytes(db);
        RunResult result = runCli("doctor", "--agent-preflight", "--format", "json",
                "--index", db.toString());
        assertEquals(0, result.exitCode, result.stderr);
        Map<?, ?> preflight = (Map<?, ?>) asObject(result.stdout).get("agent_preflight");
        assertNotNull(preflight);
        assertTrue(preflight.containsKey("status"));
        assertTrue(preflight.containsKey("blockers"));
        assertTrue(preflight.containsKey("next_commands"));
        assertFalse(preflight.containsKey("flow_coverage"));
        assertEquals(bytesBefore, Files.size(db), "preflight must not write the index");
        assertArrayEquals(contentBefore, Files.readAllBytes(db), "preflight must not change index bytes");

        Path missing = tmp.resolve("missing.db");
        RunResult absent = runCli("doctor", "--agent-preflight", "--format", "json",
                "--index", missing.toString());
        Map<?, ?> absentPreflight = (Map<?, ?>) asObject(absent.stdout).get("agent_preflight");
        assertEquals("REPAIR_REQUIRED", absentPreflight.get("status"));
        assertTrue(String.valueOf(absentPreflight.get("next_commands")).contains("anatomist index"));

        RunResult doctorHelp = runCli("doctor", "--help");
        assertTrue(doctorHelp.stdout.contains("--agent-preflight"), doctorHelp.stdout);
    }

    @Test
    void doctorAgentPreflightDetectsOfflineSourceEdits(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Path db = tmp.resolve("preflight-stale.db");
        RunResult indexed = runCli("index", project.toString(), "--no-classpath",
                "--output", db.toString());
        assertEquals(0, indexed.exitCode, indexed.stderr);
        Files.writeString(project.resolve("src/main/java/p/A.java"), """
                package p;
                class A { String changed() { return "changed"; } }
                """);
        RunResult preflight = runCli("doctor", "--agent-preflight", "--format", "json",
                "--index", db.toString());
        Map<?, ?> contract = (Map<?, ?>) asObject(preflight.stdout).get("agent_preflight");
        assertTrue(String.valueOf(contract.get("blockers")).contains("INDEX_STALE"), contract.toString());
        assertTrue(String.valueOf(contract.get("next_commands")).contains("--incremental"), contract.toString());
        assertFalse(String.valueOf(contract.get("next_commands")).contains("--dataflow"),
                contract.toString());
        assertTrue(String.valueOf(contract.get("next_commands")).contains("--no-classpath"),
                contract.toString());
    }

    @Test
    void doctorRepairCommandPreservesIndexProfileAndQuotesPaths(@TempDir Path tmp) throws Exception {
        Path holder = Files.createDirectories(tmp.resolve("project with ' quote"));
        Path project = CliTestSupport.createSimpleMavenProject(holder, false);
        Path source = project.resolve("src/main/java/p/A.java");
        Path db = holder.resolve("index with ' quote.db");
        String sourceSpec = "app@MAIN=" + project.resolve("src/main/java").toAbsolutePath();
        RunResult indexed = runCli("index", project.toString(),
                "--source-root", sourceSpec,
                "--no-classpath", "--java-version", "17", "--spring-xml",
                "--output", db.toString());
        assertEquals(0, indexed.exitCode, indexed.stderr);

        Files.writeString(source, "package p; class A { void run() { int changed = 1; } }\n");
        RunResult result = runCli("doctor", "--agent-preflight", "--format", "json",
                "--index", db.toString());
        Map<?, ?> preflight = (Map<?, ?>) asObject(result.stdout).get("agent_preflight");
        String command = String.valueOf(((List<?>) preflight.get("next_commands")).get(0));

        for (String expected : List.of(
                "--incremental", "--source-root", "--java-version 17", "--no-classpath",
                "--spring-xml", "--health-policy integrity")) {
            assertTrue(command.contains(expected), expected + " missing from: " + command);
        }
        assertFalse(command.contains("--dataflow"), command);
        assertTrue(command.contains("'\\''"), "single quote must be POSIX escaped: " + command);

        Process shell = new ProcessBuilder("sh", "-c",
                "set -- " + command + "; printf '%s\\n' \"$@\"").start();
        String parsed = new String(shell.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String shellError = new String(shell.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, shell.waitFor(), shellError);
        assertTrue(parsed.contains(sourceSpec + "\n"), parsed);
        assertTrue(parsed.contains(db.toString() + "\n"), parsed);
    }

    @Test
    void doctorRepairCommandPreservesExplicitAndDetectedClasspathProfiles(@TempDir Path tmp)
            throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Path source = project.resolve("src/main/java/p/A.java");
        Path db = tmp.resolve("profile.db");
        RunResult indexed = runCli("index", project.toString(), "--no-classpath",
                "--output", db.toString());
        assertEquals(0, indexed.exitCode, indexed.stderr);
        Files.writeString(source, "package p; class A { void changed() {} }\n");

        try (SqliteStore store = new SqliteStore(db)) {
            store.upsertProjectMeta(Map.of(
                    "classpath_mode", "explicit",
                    "classpath_override", "/tmp/one path:/tmp/two"));
        }
        RunResult full = runCli("doctor", "--agent-preflight", "--format", "json",
                "--index", db.toString());
        String fullCommand = String.valueOf(((List<?>) ((Map<?, ?>) asObject(full.stdout)
                .get("agent_preflight")).get("next_commands")).get(0));
        assertTrue(fullCommand.contains("--classpath '/tmp/one path:/tmp/two'"), fullCommand);

        try (SqliteStore store = new SqliteStore(db)) {
            store.upsertProjectMeta(Map.of(
                    "classpath_mode", "detected"));
        }
        RunResult detected = runCli("doctor", "--agent-preflight", "--format", "json",
                "--index", db.toString());
        String detectedCommand = String.valueOf(((List<?>) ((Map<?, ?>) asObject(detected.stdout)
                .get("agent_preflight")).get("next_commands")).get(0));
        assertFalse(detectedCommand.contains("--classpath"), detectedCommand);
        assertFalse(detectedCommand.contains("--no-classpath"), detectedCommand);
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
    void integrityPolicyAllowsResolutionWarningsButRejectsParseWarnings(@TempDir Path tmp)
            throws Exception {
        Path db = buildFixtureIndex(tmp, false);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO index_diagnostics"
                    + "(severity,code,phase,occurrence_count,sample) VALUES "
                    + "('warning','THIRDPARTY_SYMBOL_MISSING','full_extract_call_graph',8,'third party')");
        }

        RunResult integrity = runCli("doctor", "--health-policy", "integrity",
                "--format", "json", "--index", db.toString());
        assertEquals(0, integrity.exitCode, integrity.stderr);
        Map<?, ?> integrityJson = asObject(integrity.stdout);
        assertEquals("committed", integrityJson.get("index_state"));
        assertEquals("healthy", integrityJson.get("health"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) integrityJson.get("gate")).get("passed"));

        RunResult complete = runCli("doctor", "--strict-health",
                "--format", "json", "--index", db.toString());
        assertEquals(3, complete.exitCode, complete.stderr);
        assertEquals("degraded", asObject(complete.stdout).get("health"));

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO index_diagnostics"
                    + "(severity,code,phase,occurrence_count,sample) VALUES "
                    + "('warning','JAVA_PARSE_FAILED','PARSING',1,'bad source')");
        }
        RunResult rejected = runCli("doctor", "--health-policy", "integrity",
                "--format", "json", "--index", db.toString());
        assertEquals(3, rejected.exitCode, rejected.stderr);
    }

    @Test
    void queryEvidenceDisclosesIndeterminateEmptyWithoutChangingExitCode(@TempDir Path tmp)
            throws Exception {
        Path db = buildFixtureIndex(tmp, false);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO index_diagnostics"
                    + "(severity,code,phase,source_file,module,scope,occurrence_count,sample) VALUES "
                    + "('warning','THIRDPARTY_SYMBOL_MISSING','full_extract_call_graph',"
                    + "'service/src/main/java/example/Unknown.java','service','MAIN',2,'missing')");
            statement.executeUpdate("""
                    INSERT INTO analysis_coverage(
                      source_file,module,scope,capability,status,occurrences,
                      groups_count,codes,code_counts,details_truncated)
                    VALUES ('service/src/main/java/example/Unknown.java','service','MAIN',
                      'CALL_INCOMING','partial',2,1,
                      '["THIRDPARTY_SYMBOL_MISSING"]',
                      '{"THIRDPARTY_SYMBOL_MISSING":2}',0)
                    ON CONFLICT(source_file,module,scope,capability) DO UPDATE SET
                      status=excluded.status, occurrences=excluded.occurrences,
                      groups_count=excluded.groups_count, codes=excluded.codes,
                      code_counts=excluded.code_counts
                    """);
        }

        RunResult callers = runCli("callers-of",
                "com.example.shop.domain.dto.OrderResult#getFinalPrice()",
                "--index", db.toString());
        assertEquals(0, callers.exitCode, callers.stderr);
        Map<?, ?> evidence = (Map<?, ?>) asObject(callers.stdout).get("evidence");
        assertEquals("indeterminate", evidence.get("status"));
        assertEquals("QUERY_COVERAGE_INCOMPLETE", evidence.get("code"));
        assertEquals(Boolean.FALSE, evidence.get("negative_conclusion_safe"));
    }

    @Test
    void doctorRejectsCompatibleButEmptyIndex(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("empty.db");
        try (SqliteStore store = new SqliteStore(db)) {
            store.initSchema();
        }

        RunResult doctor = runCli("doctor", "--strict-health", "--format", "json",
                "--index", db.toString());
        assertEquals(3, doctor.exitCode, doctor.stderr);
        Map<?, ?> json = asObject(doctor.stdout);
        assertEquals("degraded", json.get("status"));
        assertEquals("unhealthy", json.get("health"));
        assertEquals("INDEX_EMPTY", ((Map<?, ?>) ((List<?>) json.get("errors")).get(0)).get("code"));
        assertEquals("degraded",
                ((Map<?, ?>) json.get("health_dimensions")).get("graph_integrity") instanceof Map<?, ?> graph
                        ? graph.get("status") : null);
        assertEquals(Boolean.FALSE, ((Map<?, ?>) json.get("gate")).get("passed"));
    }

    @Test
    void strictAliasCannotConflictWithExplicitHealthPolicy(@TempDir Path tmp) throws Exception {
        Path db = buildFixtureIndex(tmp, false);

        RunResult doctor = runCli("doctor", "--strict-health",
                "--health-policy", "integrity", "--format", "json",
                "--index", db.toString());

        assertEquals(2, doctor.exitCode, doctor.stderr);
        assertTrue(doctor.stderr.contains("complete-policy alias"));
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
