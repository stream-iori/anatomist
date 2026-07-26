package com.anatomist.cli;

import com.anatomist.test.CliTestSupport;
import com.anatomist.test.CliTestSupport.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowAnalysisIT {

    @Test
    void flowQueriesDiscloseLimitAndDepthBoundaries(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Files.writeString(project.resolve("src/main/java/p/A.java"), """
                package p;
                class A {
                    String copy(String value) {
                        String first = value;
                        String second = first;
                        String third = second;
                        return third;
                    }
                }
                """);
        Path db = tmp.resolve("flow-bounds.db");
        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--java-version", "17", "--dataflow",
                "--output", db.toString(), "--format", "json");

        RunResult limited = runCli("flow-of", "p.A#copy", "--depth", "8",
                "--limit", "1", "--index", db.toString());
        assertEquals(0, limited.exitCode(), limited.stderr());
        assertTrue(limited.stdout().contains("\"limit_truncated\" : true"), limited.stdout());
        assertTrue(limited.stdout().contains("--limit 2"), limited.stdout());
        assertTrue(limited.stdout().contains("\"negative_conclusion_safe\" : false"),
                limited.stdout());

        RunResult shallow = runCli("flow-path", "p.A#copy", "p.A#copy",
                "--from-slot", "arg:0", "--to-slot", "return", "--depth", "1",
                "--index", db.toString());
        assertEquals(0, shallow.exitCode(), shallow.stderr());
        assertTrue(shallow.stdout().contains("\"found\" : false"), shallow.stdout());
        assertTrue(shallow.stdout().contains("\"depth_truncated\" : true"), shallow.stdout());
        assertTrue(shallow.stdout().contains("QUERY_DEPTH_TRUNCATED"), shallow.stdout());
        assertTrue(shallow.stdout().contains("--depth 2"), shallow.stdout());
    }

    @Test
    void invalidTaintRuleDoesNotDisableValidRules(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Files.writeString(project.resolve("src/main/java/p/A.java"), """
                package p;
                class A {
                    String source() { return "value"; }
                    void sink(String value) {}
                    void run() { sink(source()); }
                }
                """);
        Files.createDirectories(project.resolve(".anatomist"));
        Files.writeString(project.resolve(".anatomist/taint-rules.json"), """
                {
                  "sources": [42, {"method":"p.A#source*","slot":"return"}],
                  "sinks": [{"method":"p.A#sink*","slot":"arg:0"}]
                }
                """);
        Path db = tmp.resolve("partial-rules.db");

        RunResult indexed = CliTestSupport.runIndex(project,
                "--no-classpath", "--java-version", "17", "--dataflow",
                "--output", db.toString(), "--format", "json");

        assertEquals(0, indexed.exitCode(), indexed.stderr());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            assertTrue(scalar(statement,
                    "SELECT count(*) FROM flow_nodes WHERE kind='TAINT_SOURCE'") > 0);
            assertTrue(scalar(statement,
                    "SELECT count(*) FROM flow_nodes WHERE kind='TAINT_SINK'") > 0);
        }
    }

    @Test
    void incrementalDataflowReplacesOnlyChangedFileFacts(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Path source = project.resolve("src/main/java/p/A.java");
        Files.writeString(source, """
                package p;
                class A {
                    String copy(String value) { String first = value; return first; }
                }
                """);
        Path db = tmp.resolve("incremental-flow.db");
        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--java-version", "17", "--dataflow",
                "--output", db.toString(), "--format", "json");
        int before;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            before = scalar(statement, "SELECT count(*) FROM flow_nodes");
            assertTrue(before > 0);
        }
        Files.writeString(source, """
                package p;
                class A {
                    String copy(String value) {
                        String first = value;
                        String second = first;
                        return second;
                    }
                }
                """);

        RunResult result = CliTestSupport.runIndex(project,
                "--no-classpath", "--java-version", "17", "--dataflow", "--incremental",
                "--output", db.toString(), "--format", "json");

        assertEquals(0, result.exitCode(), result.stderr());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            assertTrue(scalar(statement, "SELECT count(*) FROM flow_nodes") > before);
            assertTrue(scalar(statement,
                    "SELECT count(*) FROM flow_edges WHERE relation='DEF_USE'") >= 2);
        }
    }

    @Test
    void dataflowIsOptInPersistedAndQueryable(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Files.writeString(project.resolve("src/main/java/p/A.java"), """
                package p;
                class A {
                    String source() { return System.getProperty("input"); }
                    String copy(String value) {
                        String result = value;
                        if (result == null) result = "";
                        return result;
                    }
                    void sink(String value) {}
                    void run() { sink(copy(source())); }
                    void fail(String value) {
                        if (value == null) throw new IllegalArgumentException(value);
                    }
                }
                """);
        Files.createDirectories(project.resolve(".anatomist"));
        Files.writeString(project.resolve(".anatomist/taint-rules.json"), """
                {
                  "sources": [{"method":"java.lang.System#getProperty*","slot":"return"}],
                  "sinks": [{"method":"p.A#sink*","slot":"arg:0"}],
                  "sanitizers": []
                }
                """);
        Path plain = tmp.resolve("plain.db");
        Path flow = tmp.resolve("flow.db");

        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--java-version", "17",
                "--output", plain.toString(), "--format", "json");
        RunResult indexed = CliTestSupport.runIndex(project,
                "--no-classpath", "--java-version", "17", "--dataflow",
                "--output", flow.toString(), "--format", "json", "--timings");

        assertEquals(0, indexed.exitCode(), indexed.stderr());
        assertTrue(indexed.stdout().contains("\"flow_nodes\""), indexed.stdout());
        assertTrue(indexed.stdout().contains("\"flow_stage_write\""), indexed.stdout());
        assertTrue(indexed.stdout().contains("\"flow_relink\""), indexed.stdout());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + plain);
             Statement statement = connection.createStatement()) {
            assertEquals(0, scalar(statement, "SELECT count(*) FROM flow_nodes"));
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + flow);
             Statement statement = connection.createStatement()) {
            assertTrue(scalar(statement, "SELECT count(*) FROM flow_nodes") > 0);
            assertTrue(scalar(statement,
                    "SELECT count(*) FROM flow_edges WHERE relation='DEF_USE'") > 0);
            assertTrue(scalar(statement,
                    "SELECT count(*) FROM flow_edges WHERE relation LIKE 'GUARD_%'") > 0);
            assertTrue(scalar(statement,
                    "SELECT count(*) FROM method_flow_summaries") > 0);
            assertTrue(scalar(statement,
                    "SELECT count(*) FROM method_flow_coverage WHERE detail_level='DETAIL'") > 0);
            assertTrue(scalar(statement,
                    "SELECT count(*) FROM flow_edges WHERE relation='CALL_RETURN'") > 0);
            assertTrue(scalar(statement,
                    "SELECT count(*) FROM flow_nodes WHERE kind='TAINT_SOURCE'") > 0);
            assertTrue(scalar(statement,
                    "SELECT count(*) FROM flow_nodes WHERE kind='TAINT_SINK'") > 0);
        }

        RunResult flowOf = runCli("flow-of", "p.A#copy", "--depth", "5",
                "--index", flow.toString());
        assertEquals(0, flowOf.exitCode(), flowOf.stderr());
        assertTrue(flowOf.stdout().contains("DEF_USE"), flowOf.stdout());

        RunResult guards = runCli("guards-of", "p.A#copy", "--index", flow.toString());
        assertEquals(0, guards.exitCode(), guards.stderr());
        assertTrue(guards.stdout().contains("GUARD_TRUE"), guards.stdout());

        RunResult exceptions = runCli(
                "exception-flow", "p.A#fail", "--index", flow.toString());
        assertEquals(0, exceptions.exitCode(), exceptions.stderr());
        assertTrue(exceptions.stdout().contains("EXCEPTION_FLOW"), exceptions.stdout());

        RunResult taint = runCli("taint-path", "*", "*",
                "--depth", "30", "--index", flow.toString());
        assertEquals(0, taint.exitCode(), taint.stderr());
        assertTrue(taint.stdout().contains("\"found\" : true"), taint.stdout());

        RunResult shallowTaint = runCli("taint-path", "*", "*",
                "--depth", "1", "--index", flow.toString());
        assertEquals(0, shallowTaint.exitCode(), shallowTaint.stderr());
        assertTrue(shallowTaint.stdout().contains("\"found\" : false"), shallowTaint.stdout());
        assertTrue(shallowTaint.stdout().contains("\"depth_truncated\" : true"),
                shallowTaint.stdout());
        assertTrue(shallowTaint.stdout().contains("--depth 2"), shallowTaint.stdout());
    }

    @Test
    void summaryAndScopedModesExposeCoverageWithoutFalseNegativePaths(@TempDir Path tmp)
            throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Files.writeString(project.resolve("src/main/java/p/A.java"), """
                package p;
                class A {
                    String copy(String value) { String out = value; return out; }
                    String other(String value) { return value; }
                    String wrapper(String value) { return copy(value); }
                }
                """);

        Path summary = tmp.resolve("summary.db");
        RunResult summaryIndex = CliTestSupport.runIndex(project,
                "--no-classpath", "--java-version", "17",
                "--dataflow-mode", "summary",
                "--output", summary.toString(), "--format", "json");
        assertEquals(0, summaryIndex.exitCode(), summaryIndex.stderr());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + summary);
             Statement statement = connection.createStatement()) {
            assertEquals(0, scalar(statement, "SELECT count(*) FROM flow_nodes"));
            assertTrue(scalar(statement, "SELECT count(*) FROM method_flow_summaries") > 0);
            assertTrue(scalar(statement,
                    "SELECT count(*) FROM method_flow_coverage WHERE detail_level='SUMMARY'") > 0);
        }
        RunResult summaryQuery = runCli("flow-summary", "p.A#copy",
                "--index", summary.toString());
        assertEquals(0, summaryQuery.exitCode(), summaryQuery.stderr());
        RunResult missingDetail = runCli("flow-of", "p.A#copy",
                "--index", summary.toString());
        assertEquals(2, missingDetail.exitCode(), missingDetail.stdout());
        assertTrue(missingDetail.stdout().contains("FLOW_DETAIL_NOT_INDEXED"),
                missingDetail.stdout());

        Path scoped = tmp.resolve("scoped.db");
        RunResult scopedIndex = CliTestSupport.runIndex(project,
                "--no-classpath", "--java-version", "17",
                "--dataflow-scope", "method:p.A#copy*",
                "--output", scoped.toString(), "--format", "json");
        assertEquals(0, scopedIndex.exitCode(), scopedIndex.stderr());
        RunResult detailed = runCli("flow-of", "p.A#copy",
                "--index", scoped.toString());
        assertEquals(0, detailed.exitCode(), detailed.stderr());
        assertTrue(detailed.stdout().contains("DEF_USE"), detailed.stdout());
        RunResult outside = runCli("flow-of", "p.A#other",
                "--index", scoped.toString());
        assertEquals(2, outside.exitCode(), outside.stdout());
        RunResult incompletePath = runCli("flow-path", "p.A#copy", "p.A#wrapper",
                "--index", scoped.toString());
        assertEquals(2, incompletePath.exitCode(), incompletePath.stdout());
        assertTrue(incompletePath.stdout().contains("FLOW_COVERAGE_INCOMPLETE"),
                incompletePath.stdout());
    }

    @Test
    void rejectsConflictingFlowProfiles(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        RunResult legacyConflict = CliTestSupport.runIndex(project,
                "--no-classpath", "--java-version", "17",
                "--dataflow", "--dataflow-mode", "summary",
                "--output", tmp.resolve("legacy-conflict.db").toString());
        assertTrue(legacyConflict.exitCode() != 0);

        RunResult taintConflict = CliTestSupport.runIndex(project,
                "--no-classpath", "--java-version", "17",
                "--implicit-taint", "--dataflow-mode", "summary",
                "--output", tmp.resolve("taint-conflict.db").toString());
        assertTrue(taintConflict.exitCode() != 0);
    }

    @Test
    void flowPathUsesExactSlotsAndDataEdgesByDefault(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Files.writeString(project.resolve("src/main/java/p/A.java"), """
                package p;
                class A {
                    String copy(String value) {
                        if (value == null) return "";
                        return value;
                    }
                }
                """);
        Path db = tmp.resolve("slot-path.db");
        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--java-version", "17", "--dataflow",
                "--output", db.toString(), "--format", "json");

        RunResult path = runCli("flow-path", "p.A#copy", "p.A#copy",
                "--from-slot", "arg:0", "--to-slot", "return",
                "--index", db.toString());

        assertEquals(0, path.exitCode(), path.stderr());
        assertTrue(path.stdout().contains("\"found\" : true"), path.stdout());
        assertTrue(path.stdout().contains("\"source_slot\" : \"arg:0\""), path.stdout());
        assertTrue(path.stdout().contains("\"target_slot\" : \"return\""), path.stdout());
        assertTrue(!path.stdout().contains("\"CONTROL_FLOW\""), path.stdout());

        RunResult invalid = runCli("flow-path", "p.A#copy", "p.A#copy",
                "--from-slot", "arg:9", "--to-slot", "return",
                "--index", db.toString());
        assertEquals(2, invalid.exitCode(), invalid.stdout());
        assertTrue(invalid.stdout().contains("FLOW_ENDPOINT_SLOT_INVALID"), invalid.stdout());
    }

    @Test
    void ambiguousMethodEndpointRequiresAUniqueOverload(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Files.writeString(project.resolve("src/main/java/p/A.java"), """
                package p;
                class A {
                    String copy(String value) { return value; }
                    int copy(int value) { return value; }
                }
                """);
        Path db = tmp.resolve("ambiguous-path.db");
        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--java-version", "17", "--dataflow",
                "--output", db.toString(), "--format", "json");

        RunResult path = runCli("flow-path", "p.A#copy", "p.A#copy",
                "--from-slot", "arg:0", "--to-slot", "return",
                "--index", db.toString());

        assertEquals(2, path.exitCode(), path.stdout());
        assertTrue(path.stdout().contains("FLOW_ENDPOINT_AMBIGUOUS"), path.stdout());
    }

    @Test
    void taintPathEnforcesConfiguredSinkArgument(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Path source = project.resolve("src/main/java/p/A.java");
        Files.writeString(source, """
                package p;
                class A {
                    String source() { return "value"; }
                    void sink(String first, String second) {}
                    void run() { sink(source(), "safe"); }
                }
                """);
        Files.createDirectories(project.resolve(".anatomist"));
        Files.writeString(project.resolve(".anatomist/taint-rules.json"), """
                {
                  "sources": [{"method":"p.A#source*","slot":"return"}],
                  "sinks": [{"method":"p.A#sink*","slot":"arg:1"}]
                }
                """);
        Path wrongSlot = tmp.resolve("taint-wrong-slot.db");
        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--java-version", "17", "--dataflow",
                "--output", wrongSlot.toString(), "--format", "json");

        RunResult missing = runCli("taint-path", "*", "*",
                "--index", wrongSlot.toString());
        assertEquals(0, missing.exitCode(), missing.stderr());
        assertTrue(missing.stdout().contains("\"found\" : false"), missing.stdout());

        Files.writeString(source, """
                package p;
                class A {
                    String source() { return "value"; }
                    void sink(String first, String second) {}
                    void run() { sink("safe", source()); }
                }
                """);
        Path rightSlot = tmp.resolve("taint-right-slot.db");
        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--java-version", "17", "--dataflow",
                "--output", rightSlot.toString(), "--format", "json");

        RunResult found = runCli("taint-path", "*", "*",
                "--index", rightSlot.toString());
        assertEquals(0, found.exitCode(), found.stderr());
        assertTrue(found.stdout().contains("\"found\" : true"), found.stdout());
    }

    @Test
    void incrementalScopedFlowKeepsCoverageAndProfileChangesRebuild(@TempDir Path tmp)
            throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Path source = project.resolve("src/main/java/p/A.java");
        Files.writeString(source, """
                package p;
                class A {
                    String copy(String value) { return value; }
                    String other(String value) { return value; }
                }
                """);
        Path db = tmp.resolve("incremental-scoped.db");
        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--java-version", "17",
                "--dataflow-scope", "method:p.A#copy*",
                "--output", db.toString());
        Files.writeString(source, """
                package p;
                class A {
                    String copy(String value) { String first = value; return first; }
                    String other(String value) { return value; }
                }
                """);
        RunResult incremental = CliTestSupport.runIndex(project,
                "--no-classpath", "--java-version", "17", "--incremental",
                "--dataflow-scope", "method:p.A#copy*",
                "--output", db.toString(), "--format", "json");
        assertEquals(0, incremental.exitCode(), incremental.stderr());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            assertTrue(scalar(statement,
                    "SELECT count(*) FROM flow_edges WHERE relation='DEF_USE'") > 0);
            assertTrue(scalar(statement,
                    "SELECT count(*) FROM method_flow_coverage WHERE detail_level='DETAIL'") > 0);
            assertTrue(scalar(statement,
                    "SELECT count(*) FROM method_flow_coverage WHERE detail_level='SUMMARY'") > 0);
        }

        RunResult changedProfile = CliTestSupport.runIndex(project,
                "--no-classpath", "--java-version", "17", "--incremental",
                "--dataflow-mode", "summary",
                "--output", db.toString(), "--format", "json");
        assertEquals(0, changedProfile.exitCode(), changedProfile.stderr());
        assertTrue(changedProfile.stderr().contains("flow profile changed"),
                changedProfile.stderr());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            assertEquals(0, scalar(statement, "SELECT count(*) FROM flow_nodes"));
            assertEquals(0, scalar(statement,
                    "SELECT count(*) FROM method_flow_coverage WHERE detail_level='DETAIL'"));
        }
    }

    private static int scalar(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static RunResult runCli(String... args) throws Exception {
        return CliTestSupport.capture(() -> new CommandLine(new AnatomistCli()).execute(args));
    }
}
