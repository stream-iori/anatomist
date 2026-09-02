package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.core.IndexDiagnostic;
import com.anatomist.store.SqliteStore;
import com.anatomist.test.CliTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeclarationsOfCommandIT {
    private static final String AUTH = "src/main/java/com/example/AuthenticationService.java";
    @TempDir Path tmp;
    Path project;
    Path db;

    @BeforeEach void index() throws Exception {
        project = tmp.resolve("project");
        CliTestSupport.copyDir(CliTestSupport.repoRoot().resolve("fixtures/declarations"), project);
        db = tmp.resolve("declarations.db");
        CliTestSupport.assertIndexOk(project, "--no-classpath", "--java-version", "21",
                "--output", db.toString(), "--format", "json");
    }

    @Test void dioramaSeedFilterExcludesNestedRecordAndPrivateMethod() throws Exception {
        var run = command("--file", AUTH, "--visibility", "public,protected", "--kind", "type,method",
                "--top-level-types", "--direct-members", "--format", "json");
        assertEquals(0, run.exitCode(), run.stderr());
        List<Map<?, ?>> rows = rows(run.stdout());
        assertEquals(List.of("com.example.AuthenticationService", "com.example.AuthenticationService#authenticate()",
                        "com.example.AuthenticationService#convert(java.util.List,java.lang.String...)",
                        "com.example.AuthenticationService#overload(java.lang.String)",
                        "com.example.AuthenticationService#overload(int)"),
                rows.stream().map(row -> String.valueOf(row.get("symbol_id"))).toList());
        assertTrue(run.stdout().contains("\"coverage\" : \"complete\""), run.stdout());
        assertTrue(run.stdout().contains("\"negative_conclusion_safe\" : true"), run.stdout());
    }

    @Test void classifiesConstructorsNestedTypesAndDirectOwnership() throws Exception {
        var run = command("--file", AUTH, "--format", "json");
        assertEquals(0, run.exitCode(), run.stderr());
        List<Map<?, ?>> rows = rows(run.stdout());
        Map<?, ?> nested = one(rows, "com.example.AuthenticationService.AuthResult");
        assertEquals("type", nested.get("declaration_kind"));
        assertEquals("record", nested.get("type_kind"));
        assertEquals(1, ((Number) nested.get("nesting_depth")).intValue());
        assertFalse((Boolean) nested.get("direct_member"));
        Map<?, ?> constructor = rows.stream().filter(row -> "constructor".equals(row.get("declaration_kind")))
                .filter(row -> "com.example.AuthenticationService".equals(row.get("declaring_type"))).findFirst().orElseThrow();
        assertEquals("CONSTRUCTOR", constructor.get("kind"));
        assertEquals("public", constructor.get("visibility"));
        Map<?, ?> nestedMethod = rows.stream().filter(row -> "message".equals(row.get("label"))).findFirst().orElseThrow();
        assertEquals("com.example.AuthenticationService.AuthResult", nestedMethod.get("declaring_type"));
        assertFalse((Boolean) nestedMethod.get("direct_member"));
        assertTrue(rows.stream().noneMatch(row -> "AuthResult".equals(row.get("label"))
                && "method".equals(row.get("declaration_kind"))));
        assertTrue(rows.stream().noneMatch(row -> "valid".equals(row.get("label"))), "synthetic accessor is opt-in");
        assertTrue(rows.stream().anyMatch(row -> "internalOnly".equals(row.get("label"))
                && "private".equals(row.get("visibility"))));
        assertEquals("ANNOTATION", rows.stream().filter(row -> "TypeUse".equals(row.get("label")))
                .findFirst().orElseThrow().get("kind"));

        var synthetic = command("--file", AUTH, "--kind", "method", "--include-synthetic", "--format", "json");
        assertEquals(0, synthetic.exitCode(), synthetic.stderr());
        assertTrue(rows(synthetic.stdout()).stream().anyMatch(row -> "valid".equals(row.get("label"))
                && Boolean.TRUE.equals(row.get("synthetic"))));
    }

    @Test void reportsImplicitInterfaceVisibilityAndPagination() throws Exception {
        var run = command("--file", "src/main/java/com/example/Contract.java", "--kind", "method",
                "--limit", "1", "--format", "json");
        assertEquals(0, run.exitCode(), run.stderr());
        Map<?, ?> json = map(run.stdout());
        Map<?, ?> first = (Map<?, ?>) ((List<?>) json.get("results")).getFirst();
        assertEquals("public", first.get("visibility"));
        assertTrue(((List<?>) first.get("implicit_modifiers")).contains("public"));
        assertTrue(((List<?>) first.get("implicit_modifiers")).contains("abstract"));
        Map<?, ?> stats = (Map<?, ?>) json.get("stats");
        assertEquals(true, stats.get("truncated"));
        assertEquals(true, ((Map<?, ?>) json.get("budget")).get("truncated"));

        var type = command("--file", "src/main/java/com/example/Contract.java", "--kind", "type", "--format", "json");
        assertEquals("package", rows(type.stdout()).getFirst().get("visibility"));

        var enumType = command("--file", "src/main/java/com/example/Mode.java", "--kind", "type", "--format", "json");
        assertEquals("enum", rows(enumType.stdout()).getFirst().get("type_kind"));
    }

    @Test void incrementalReplacementRefreshesDeclarationRows() throws Exception {
        Path source = project.resolve(AUTH);
        Files.writeString(source, Files.readString(source).replace(
                "private void internalOnly() {}", "public native void replacement();"));
        CliTestSupport.assertIndexOk(project, "--incremental", "--no-classpath", "--java-version", "21",
                "--output", db.toString(), "--format", "json");
        var run = command("--file", AUTH, "--visibility", "public", "--kind", "method", "--format", "json");
        assertEquals(0, run.exitCode(), run.stderr());
        Map<?, ?> replacement = rows(run.stdout()).stream().filter(row -> "replacement".equals(row.get("label")))
                .findFirst().orElseThrow();
        assertTrue(((List<?>) replacement.get("modifiers")).contains("native"));
        assertTrue(rows(run.stdout()).stream().noneMatch(row -> "internalOnly".equals(row.get("label"))));
    }

    @Test void staleAndMissingFilesFailClosed() throws Exception {
        Files.writeString(project.resolve(AUTH), Files.readString(project.resolve(AUTH)) + "\n// changed\n");
        var stale = command("--file", AUTH, "--format", "json");
        assertEquals(3, stale.exitCode());
        assertTrue(stale.stdout().contains("INDEX_STALE"), stale.stdout());
        assertTrue(stale.stdout().contains("\"negative_conclusion_safe\" : false"), stale.stdout());

        var missing = command("--file", "src/main/java/com/example/Missing.java", "--format", "json");
        assertEquals(3, missing.exitCode());
        assertTrue(missing.stdout().contains("FILE_NOT_INDEXED"), missing.stdout());
    }

    @Test void incompleteSourceLayoutFailsClosed() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "UPDATE project_meta SET value='broken' WHERE key='source_layout'"));
        }

        var run = command("--file", AUTH, "--format", "json");

        assertEquals(3, run.exitCode());
        assertTrue(run.stdout().contains("SOURCE_PROFILE_INCOMPLETE"), run.stdout());
        assertTrue(run.stdout().contains("\"negative_conclusion_safe\" : false"), run.stdout());
    }

    @Test void persistedParseFailureFailsClosed() throws Exception {
        try (SqliteStore store = new SqliteStore(db)) {
            store.replaceIndexDiagnostics(List.of(new IndexDiagnostic(
                    "warning", "JAVA_PARSE_FAILED", "PARSING", AUTH, ".", "MAIN",
                    null, 1, "fixture parse failure")));
        }
        var run = command("--file", AUTH, "--format", "json");
        assertEquals(3, run.exitCode());
        assertTrue(run.stdout().contains("FILE_PARSE_FAILED"), run.stdout());
        assertTrue(run.stdout().contains("\"coverage\" : \"incomplete\""), run.stdout());
    }

    private CliTestSupport.RunResult command(String... args) throws Exception {
        String[] all = new String[args.length + 3]; all[0] = "declarations-of";
        System.arraycopy(args, 0, all, 1, args.length); all[all.length - 2] = "--index";
        all[all.length - 1] = db.toString();
        return CliTestSupport.capture(() -> new CommandLine(new AnatomistCli()).execute(all));
    }
    @SuppressWarnings("unchecked") private static List<Map<?, ?>> rows(String json) {
        return (List<Map<?, ?>>) map(json).get("results");
    }
    private static Map<?, ?> map(String json) { return (Map<?, ?>) Json.parseTree(json); }
    private static Map<?, ?> one(List<Map<?, ?>> rows, String symbol) {
        return rows.stream().filter(row -> symbol.equals(row.get("symbol_id"))).findFirst().orElseThrow();
    }
}
