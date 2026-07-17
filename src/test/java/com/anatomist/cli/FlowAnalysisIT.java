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
                "--output", flow.toString(), "--format", "json");

        assertEquals(0, indexed.exitCode(), indexed.stderr());
        assertTrue(indexed.stdout().contains("\"flow_nodes\""), indexed.stdout());
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
