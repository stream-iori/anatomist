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
        assertTrue(run.stdout().contains("\"contract\" : \"semantic-stream/v1\""), run.stdout());
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
        assertEquals("CONSTRUCTOR", constructor.get("storage_kind"));
        assertEquals("public", constructor.get("visibility"));
        Map<?, ?> nestedMethod = rows.stream().filter(row -> "message".equals(row.get("label"))).findFirst().orElseThrow();
        assertEquals("com.example.AuthenticationService.AuthResult", nestedMethod.get("declaring_type"));
        assertFalse((Boolean) nestedMethod.get("direct_member"));
        assertTrue(rows.stream().noneMatch(row -> "AuthResult".equals(row.get("label"))
                && "method".equals(row.get("declaration_kind"))));
        assertTrue(rows.stream().noneMatch(row -> "valid".equals(row.get("label"))), "synthetic accessor is opt-in");
        assertTrue(rows.stream().anyMatch(row -> "internalOnly".equals(row.get("label"))
                && "private".equals(row.get("visibility"))));
        assertEquals("ANNOTATION", rows.stream().filter(row -> "TypeUse".equals(row.get("name")))
                .findFirst().orElseThrow().get("storage_kind"));

        var synthetic = command("--file", AUTH, "--kind", "method", "--include-synthetic", "--format", "json");
        assertEquals(0, synthetic.exitCode(), synthetic.stderr());
        assertTrue(rows(synthetic.stdout()).stream().anyMatch(row -> "valid".equals(row.get("label"))
                && Boolean.TRUE.equals(row.get("synthetic"))));
    }

    @Test void reportsImplicitInterfaceVisibilityAndPagination() throws Exception {
        var run = command("--file", "src/main/java/com/example/Contract.java", "--kind", "method",
                "--limit", "1", "--format", "json");
        assertEquals(0, run.exitCode(), run.stderr());
        Map<?, ?> first = rows(run.stdout()).getFirst();
        assertEquals("public", first.get("visibility"));
        assertTrue(((List<?>) first.get("implicit_modifiers")).contains("public"));
        assertTrue(((List<?>) first.get("implicit_modifiers")).contains("abstract"));
        Map<?, ?> evidence = records(run.stdout()).stream()
                .filter(row -> "seed".equals(row.get("scope"))).findFirst().orElseThrow();
        assertEquals(true, evidence.get("truncated"));
        Map<?, ?> page = (Map<?, ?>) evidence.get("page");
        assertEquals(0, ((Number) page.get("offset")).intValue());
        assertEquals(1, ((Number) page.get("returned")).intValue());
        assertEquals(1, ((Number) page.get("next_offset")).intValue());

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
        assertTrue(stale.stderr().contains("INDEX_STALE"), stale.stderr());

        var missing = command("--file", "src/main/java/com/example/Missing.java", "--format", "json");
        assertEquals(3, missing.exitCode());
        assertTrue(missing.stderr().contains("FILE_NOT_INDEXED"), missing.stderr());
    }

    @Test void incompleteSourceLayoutFailsClosed() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "UPDATE project_meta SET value='broken' WHERE key='source_layout'"));
        }

        var run = command("--file", AUTH, "--format", "json");

        assertEquals(3, run.exitCode());
        assertTrue(run.stderr().contains("SOURCE_PROFILE_INCOMPLETE"), run.stderr());
    }

    @Test void missingEmbeddedDeclarationFacetFailsClosed() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            assertTrue(statement.executeUpdate("""
                    UPDATE nodes SET declaration_kind=NULL,type_kind=NULL,visibility=NULL,modifiers=NULL,
                        declared_modifiers=NULL,implicit_modifiers=NULL,declaring_type=NULL,declaration_namespace=NULL,nesting_depth=NULL,
                        declaration_source_location=NULL,declaration_begin_line=NULL,declaration_begin_column=NULL,
                        declaration_end_line=NULL,declaration_end_column=NULL,direct_member=NULL,synthetic=NULL,binding_resolved=NULL
                    WHERE source_file='src/main/java/com/example/Contract.java' AND kind='INTERFACE'
                    """) > 0);
        }

        var run = command("--file", "src/main/java/com/example/Contract.java", "--format", "json");

        assertEquals(3, run.exitCode());
        assertTrue(run.stderr().contains("DECLARATION_COVERAGE_INCOMPLETE"), run.stderr());
    }

    @Test void missingIndexKeepsStructuredStandaloneError() throws Exception {
        Path missing = tmp.resolve("missing.db");
        CliTestSupport.RunResult run = CliTestSupport.capture(() ->
                new CommandLine(new AnatomistCli()).execute(
                        "declarations-of", "--file", AUTH, "--index", missing.toString()));
        assertEquals(3, run.exitCode());
        assertTrue(run.stderr().contains("\"code\":\"INDEX_MISSING\""), run.stderr());
        assertTrue(run.stderr().contains("\"contract\":\"anatomist-error/v1\""), run.stderr());
        assertFalse(run.stderr().contains("\tat "), run.stderr());
    }

    @Test void persistedParseFailureFailsClosed() throws Exception {
        try (SqliteStore store = new SqliteStore(db)) {
            store.replaceIndexDiagnostics(List.of(new IndexDiagnostic(
                    "warning", "JAVA_PARSE_FAILED", "PARSING", AUTH, ".", "MAIN",
                    null, 1, "fixture parse failure")));
        }
        var run = command("--file", AUTH, "--format", "json");
        assertEquals(3, run.exitCode());
        assertTrue(run.stderr().contains("FILE_PARSE_FAILED"), run.stderr());
    }

    private CliTestSupport.RunResult command(String... args) throws Exception {
        String[] all = new String[args.length + 3]; all[0] = "declarations-of";
        System.arraycopy(args, 0, all, 1, args.length); all[all.length - 2] = "--index";
        all[all.length - 1] = db.toString();
        return CliTestSupport.capture(() -> new CommandLine(new AnatomistCli()).execute(all));
    }
    private static List<Map<?, ?>> rows(String json) {
        List<Map<?, ?>> result = new java.util.ArrayList<>();
        records(json).stream().filter(row -> "entity".equals(row.get("record"))).forEach(row -> {
            Map<Object,Object> flattened = new java.util.LinkedHashMap<>(); flattened.putAll(row);
            flattened.put("label", row.get("name"));
            if (row.get("facets") instanceof Map<?,?> facets) flattened.putAll(facets);
            result.add(flattened);
        });
        return result;
    }
    @SuppressWarnings("unchecked") private static List<Map<?, ?>> records(String json) {
        Map<?, ?> envelope = (Map<?, ?>) Json.parseTree(json);
        List<Map<?, ?>> records = new java.util.ArrayList<>((List<Map<?, ?>>) envelope.get("results"));
        Map<?, ?> evidence = (Map<?, ?>) envelope.get("evidence");
        records.addAll((List<Map<?, ?>>) evidence.get("seeds"));
        records.add((Map<?, ?>) evidence.get("stream"));
        return records;
    }
    private static Map<?, ?> one(List<Map<?, ?>> rows, String symbol) {
        return rows.stream().filter(row -> symbol.equals(row.get("symbol_id"))).findFirst().orElseThrow();
    }
}
