package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.test.CliTestSupport;
import com.anatomist.test.CliTestSupport.RunResult;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionAnalysisIT {

    @Test
    void defaultIndexExposesReflectionThroughExistingQueries(@TempDir Path tmp) throws Exception {
        Fixture fixture = createProject(tmp);
        index(fixture, false);

        Map<?, ?> callees = query("callees-of", "p.Caller#run", "--depth", "2",
                "--index", fixture.db().toString());
        Map<?, ?> reflectionCall = results(callees).stream()
                .filter(row -> "REFLECTION".equals(row.get("call_kind")))
                .filter(row -> "p.Target#echo(java.lang.String)"
                        .equals(row.get("target_symbol_id")))
                .findFirst().orElseThrow(() -> new AssertionError(callees.toString()));
        assertEquals("INFERRED", reflectionCall.get("confidence"));
        assertTrue(((String) reflectionCall.get("metadata"))
                .contains("\"operation\":\"METHOD_INVOKE\""));
        assertTrue(results(callees).stream().anyMatch(row ->
                "REFLECTION".equals(row.get("call_kind"))
                        && ((String) row.get("metadata"))
                        .contains("\"operation\":\"CONSTRUCTOR_NEW_INSTANCE\"")),
                callees.toString());

        Map<?, ?> deps = query("deps-of", "p.Caller",
                "--index", fixture.db().toString());
        assertTrue(results(deps).stream().anyMatch(row ->
                "REFERENCES".equals(row.get("relation"))
                        && "p.Target".equals(row.get("target_symbol_id"))
                        && ((String) row.get("metadata"))
                        .contains("\"operation\":\"CLASS_FOR_NAME\"")), deps.toString());
        assertTrue(results(deps).stream().anyMatch(row ->
                "REFERENCES".equals(row.get("relation"))
                        && "p.Target#echo(java.lang.String)"
                        .equals(row.get("target_symbol_id"))), deps.toString());
        assertTrue(results(deps).stream().anyMatch(row ->
                "REFERENCES".equals(row.get("relation"))
                        && "p.Outer.Inner".equals(row.get("target_symbol_id"))),
                deps.toString());

        Map<?, ?> callers = query("callers-of", "p.Target#echo",
                "--index", fixture.db().toString());
        assertTrue(results(callers).stream().anyMatch(row ->
                "REFLECTION".equals(row.get("call_kind"))
                        && "p.Caller#run()".equals(row.get("source_symbol_id"))),
                callers.toString());

        Map<?, ?> path = query("call-path", "p.Caller#run", "p.Helper#done",
                "--depth", "3", "--index", fixture.db().toString());
        assertTrue(results(path).stream().anyMatch(row ->
                "REFLECTION".equals(row.get("call_kind"))), path.toString());
        assertTrue(results(path).stream().anyMatch(row ->
                "p.Helper#done(java.lang.String)".equals(row.get("target_symbol_id"))),
                path.toString());

        assertEquals(0, scalar(fixture.db(),
                "SELECT count(*) FROM flow_nodes"),
                "reflection must be available without --dataflow");
    }

    @Test
    void incrementalReplacementMatchesFreshGraph(@TempDir Path tmp) throws Exception {
        Fixture fixture = createProject(tmp);
        index(fixture, false);

        String source = Files.readString(fixture.caller(), StandardCharsets.UTF_8);
        Files.writeString(fixture.caller(), source
                .replace("\"echo\"", "\"alternate\""), StandardCharsets.UTF_8);
        index(fixture, true);

        assertEquals(0, scalar(fixture.db(), """
                SELECT count(*) FROM edges
                WHERE relation='CALLS' AND call_kind='REFLECTION'
                  AND metadata LIKE '%"operation":"METHOD_INVOKE"%'
                  AND EXISTS (
                    SELECT 1 FROM nodes target
                    WHERE target.id=edges.target_id
                      AND target.symbol_id='p.Target#echo(java.lang.String)')
                """));
        assertEquals(1, scalar(fixture.db(), """
                SELECT count(*) FROM edges
                WHERE relation='CALLS' AND call_kind='REFLECTION'
                  AND metadata LIKE '%"operation":"METHOD_INVOKE"%'
                  AND EXISTS (
                    SELECT 1 FROM nodes target
                    WHERE target.id=edges.target_id
                      AND target.symbol_id='p.Target#alternate(java.lang.String)')
                """));

        Path fresh = tmp.resolve("fresh.db");
        Fixture freshFixture = new Fixture(fixture.project(), fixture.sourceRoot(),
                fixture.caller(), fresh);
        index(freshFixture, false);
        assertEquals(canonicalReflectionGraph(fresh), canonicalReflectionGraph(fixture.db()));
    }

    @Test
    void dataflowDoesNotDuplicateReflectionFacts(@TempDir Path tmp) throws Exception {
        Fixture fixture = createProject(tmp);
        RunResult indexed = CliTestSupport.runIndex(fixture.project(),
                "--project-source", fixture.sourceRoot().toString(),
                "--no-classpath", "--java-version", "17", "--dataflow",
                "--output", fixture.db().toString());
        assertEquals(0, indexed.exitCode(), indexed.stderr());
        assertEquals(1, scalar(fixture.db(), """
                SELECT count(*) FROM edges
                WHERE relation='CALLS' AND call_kind='REFLECTION'
                  AND metadata LIKE '%"operation":"METHOD_INVOKE"%'
                """));
        assertTrue(scalar(fixture.db(), "SELECT count(*) FROM flow_nodes") > 0);
    }

    private static Fixture createProject(Path tmp) throws Exception {
        Path project = tmp.resolve("reflection-project");
        Path sourceRoot = project.resolve("src/main/java");
        Path pkg = sourceRoot.resolve("p");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Target.java"), """
                package p;
                public class Target {
                    public String echo(String value) { return Helper.done(value); }
                    public String alternate(String value) { return Helper.done(value); }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(pkg.resolve("Helper.java"), """
                package p;
                public class Helper {
                    public static String done(String value) { return value; }
                }
                """, StandardCharsets.UTF_8);
        Path caller = pkg.resolve("Caller.java");
        Files.writeString(caller, """
                package p;
                import java.lang.reflect.Constructor;
                import java.lang.reflect.Method;
                public class Caller {
                    public void run() throws Exception {
                        String className = "p." + "Target";
                        Class<?> type = Class.forName(className);
                        Class.forName("p.Outer$Inner");
                        Method method = type.getMethod("echo", String.class);
                        method.invoke(new Target(), "value");
                        Constructor<?> constructor = type.getConstructor();
                        constructor.newInstance();
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(pkg.resolve("Outer.java"), """
                package p;
                public class Outer {
                    public static class Inner {}
                }
                """, StandardCharsets.UTF_8);
        return new Fixture(project, sourceRoot, caller, tmp.resolve("reflection.db"));
    }

    private static void index(Fixture fixture, boolean incremental) throws Exception {
        List<String> args = new java.util.ArrayList<>(List.of(
                "--project-source", fixture.sourceRoot().toString(),
                "--no-classpath", "--java-version", "17",
                "--output", fixture.db().toString()));
        if (incremental) args.add("--incremental");
        RunResult indexed = CliTestSupport.runIndex(
                fixture.project(), args.toArray(String[]::new));
        assertEquals(0, indexed.exitCode(),
                "stdout:\n" + indexed.stdout() + "\nstderr:\n" + indexed.stderr());
    }

    private static Map<?, ?> query(String... args) throws Exception {
        RunResult result = CliTestSupport.capture(
                () -> new CommandLine(new AnatomistCli()).execute(args));
        assertEquals(0, result.exitCode(), result.stderr());
        return (Map<?, ?>) Json.parseTree(result.stdout());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> results(Map<?, ?> envelope) {
        return (List<Map<?, ?>>) envelope.get("results");
    }

    private static int scalar(Path db, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static List<String> canonicalReflectionGraph(Path db) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT source.symbol_id || '|' || edges.relation || '|'
                         || COALESCE(target.symbol_id, edges.external_target_fqn) || '|'
                         || COALESCE(edges.call_kind, '') || '|' || edges.metadata
                     FROM edges
                     JOIN nodes source ON source.id=edges.source_id
                     LEFT JOIN nodes target ON target.id=edges.target_id
                     WHERE edges.metadata LIKE '%"via":"reflection"%'
                     ORDER BY 1
                     """)) {
            List<String> rows = new java.util.ArrayList<>();
            while (result.next()) rows.add(result.getString(1));
            return rows;
        }
    }

    private record Fixture(Path project, Path sourceRoot, Path caller, Path db) {}
}
