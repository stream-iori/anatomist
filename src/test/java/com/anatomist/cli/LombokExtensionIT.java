package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.query.QueryService;
import com.anatomist.test.CliTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

class LombokExtensionIT {

    @Test
    void defaultIsOffAndStrictConfigUsesCompleteHealthGate(@TempDir Path tmp) throws Exception {
        Path project = copyFixture(tmp);
        Path offDb = tmp.resolve("off.db");
        var off = CliTestSupport.runIndex(project,
                "--no-classpath", "--java-version", "25",
                "--output", offDb.toString(), "--format", "json");
        assertEquals(0, off.exitCode(), off.stderr());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + offDb);
             Statement statement = connection.createStatement()) {
            assertEquals(0, scalar(statement,
                    "SELECT count(*) FROM nodes WHERE producer_id='lombok-ast'"));
            assertEquals(0, scalar(statement,
                    "SELECT count(*) FROM nodes WHERE json_extract(metadata,'$.lombok') IS NOT NULL"));
            assertEquals("off", scalarString(statement,
                    "SELECT value FROM project_meta WHERE key='lombok_mode'"));
        }

        Path configDirectory = Files.createDirectories(project.resolve(".anatomist"));
        Files.writeString(configDirectory.resolve("config.toml"), """
                [index]
                java_version = 25
                [extensions.lombok]
                mode = "ast"
                strict = true
                """);
        Path strictDb = tmp.resolve("strict.db");
        var strict = CliTestSupport.runIndex(project,
                "--no-classpath", "--output", strictDb.toString(), "--format", "json");
        assertEquals(3, strict.exitCode(), strict.stderr());
        assertTrue(strict.stdout().contains("\"health\" : \"unhealthy\""), strict.stdout());
        assertTrue(strict.stdout().contains("LOMBOK_FEATURE_UNSUPPORTED"), strict.stdout());
        assertFalse(Files.exists(strictDb), "a failed initial build must not publish an index");
    }

    @Test
    void astModeIndexesSyntheticMembersCallsDiagnosticsAndMetadata(@TempDir Path tmp) throws Exception {
        Path project = copyFixture(tmp);
        Path db = tmp.resolve("lombok.db");

        var indexed = CliTestSupport.runIndex(project,
                "--no-classpath", "--java-version", "25", "--lombok", "ast",
                "--output", db.toString(), "--format", "json", "--timings");

        assertEquals(0, indexed.exitCode(), indexed.stderr());
        assertTrue(indexed.stdout().contains("extension_ast_augment"), indexed.stdout());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            assertEquals(1, scalar(statement, "SELECT count(*) FROM nodes "
                    + "WHERE symbol_id='sample.User#getName()' AND producer_id='lombok-ast'"));
            assertEquals(1, scalar(statement, "SELECT count(*) FROM nodes "
                    + "WHERE symbol_id='sample.User#isActive()' AND producer_id='lombok-ast'"));
            assertEquals(1, scalar(statement, "SELECT count(*) FROM nodes "
                    + "WHERE symbol_id='sample.LogService#log' AND producer_id='lombok-ast'"));
            assertEquals(1, scalar(statement, "SELECT count(*) FROM edges "
                    + "WHERE target_id IN (SELECT id FROM nodes WHERE symbol_id='sample.User#getName()') "
                    + "AND relation='CALLS' AND is_external=0"));
            assertTrue(scalar(statement, "SELECT count(*) FROM index_diagnostics "
                    + "WHERE code='LOMBOK_FEATURE_UNSUPPORTED' AND symbol='Builder'") >= 1);
            assertEquals(Path.of("src/main/java/sample/UnsupportedBuilder.java").toString(), scalarString(statement,
                    "SELECT source_file FROM index_diagnostics "
                            + "WHERE code='LOMBOK_FEATURE_UNSUPPORTED' AND symbol='Builder' "
                            + "AND source_file='src/main/java/sample/UnsupportedBuilder.java' LIMIT 1"));
            assertEquals("ast", scalarString(statement,
                    "SELECT value FROM project_meta WHERE key='lombok_mode'"));
            assertEquals("2", scalarString(statement,
                    "SELECT value FROM project_meta WHERE key='lombok_extension_version'"));
            assertEquals("partial", scalarString(statement,
                    "SELECT value FROM project_meta WHERE key='lombok_coverage'"));
            assertTrue(Long.parseLong(scalarString(statement,
                    "SELECT value FROM project_meta WHERE key='lombok_generated_members'")) > 0);
            assertEquals("complete", scalarString(statement,
                    "SELECT json_extract(metadata,'$.lombok.coverage') FROM nodes "
                            + "WHERE symbol_id='sample.User'"));
            assertEquals("none", scalarString(statement,
                    "SELECT json_extract(metadata,'$.lombok.coverage') FROM nodes "
                            + "WHERE symbol_id='sample.UnsupportedBuilder'"));
            assertEquals(0, scalar(statement, "SELECT count(*) FROM nodes "
                    + "WHERE symbol_id IN ('sample.AccessorUser#getName()',"
                    + "'sample.AccessorUser#setName(java.lang.String)')"));
            assertEquals("partial", scalarString(statement,
                    "SELECT json_extract(metadata,'$.lombok.coverage') FROM nodes "
                            + "WHERE symbol_id='sample.AccessorUser'"));
            assertEquals("mapped", scalarString(statement,
                    "SELECT json_extract(metadata,'$.lombok_usage.mapping_status') FROM edges "
                            + "WHERE source_id IN (SELECT id FROM nodes "
                            + "WHERE symbol_id='sample.LombokUsageService#accessor(sample.AccessorUser)') "
                            + "AND relation='CALLS' AND external_target_fqn='sample.AccessorUser#name()'"));
            assertEquals("sample.AccessorUser#name", scalarString(statement,
                    "SELECT json_extract(metadata,'$.lombok_usage.field_id') FROM edges "
                            + "WHERE source_id IN (SELECT id FROM nodes "
                            + "WHERE symbol_id='sample.LombokUsageService#accessor(sample.AccessorUser)') "
                            + "AND relation='CALLS' AND external_target_fqn='sample.AccessorUser#name()'"));
            assertEquals("builder", scalarString(statement,
                    "SELECT json_extract(metadata,'$.lombok_usage.capability') FROM edges "
                            + "WHERE source_id IN (SELECT id FROM nodes "
                            + "WHERE symbol_id='sample.LombokUsageService#build()') "
                            + "AND relation='CALLS' "
                            + "AND external_target_fqn='sample.UnsupportedBuilder#builder()'"));
            assertEquals("value", scalarString(statement,
                    "SELECT json_extract(metadata,'$.lombok_usage.steps[0].field') FROM edges "
                            + "WHERE source_id IN (SELECT id FROM nodes "
                            + "WHERE symbol_id='sample.LombokUsageService#build()') "
                            + "AND relation='CALLS' "
                            + "AND external_target_fqn='sample.UnsupportedBuilder#builder()'"));
            assertEquals(0, scalar(statement, "SELECT count(*) FROM nodes "
                    + "WHERE symbol_id LIKE 'sample.UnsupportedBuilder%Builder%'"));
            assertEquals("sample.CustomBuilder#label", scalarString(statement,
                    "SELECT json_extract(metadata,'$.lombok_usage.steps[0].field_id') FROM edges "
                            + "WHERE source_id IN (SELECT id FROM nodes "
                            + "WHERE symbol_id='sample.LombokUsageService#customBuild()') "
                            + "AND external_target_fqn='sample.CustomBuilder#newBuilder()'"));
            assertEquals("create", scalarString(statement,
                    "SELECT json_extract(metadata,'$.lombok_usage.steps[1].method') FROM edges "
                            + "WHERE source_id IN (SELECT id FROM nodes "
                            + "WHERE symbol_id='sample.LombokUsageService#customBuild()') "
                            + "AND external_target_fqn='sample.CustomBuilder#newBuilder()'"));
            assertEquals("sample.PrefixAccessor#mName", scalarString(statement,
                    "SELECT json_extract(metadata,'$.lombok_usage.field_id') FROM edges "
                            + "WHERE source_id IN (SELECT id FROM nodes "
                            + "WHERE symbol_id='sample.LombokUsageService#prefixed(sample.PrefixAccessor)') "
                            + "AND external_target_fqn='sample.PrefixAccessor#getName()'"));
            assertNull(scalarString(statement,
                    "SELECT json_extract(metadata,'$.lombok_usage') FROM edges "
                            + "WHERE source_id IN (SELECT id FROM nodes "
                            + "WHERE symbol_id='sample.LombokUsageService#disabled(sample.DisabledAccessor)') "
                            + "AND external_target_fqn='sample.DisabledAccessor#getSecret()'"));
            assertEquals("sample.BaseModel#base", scalarString(statement,
                    "SELECT json_extract(metadata,'$.lombok_usage.steps[0].field_id') FROM edges "
                            + "WHERE source_id IN (SELECT id FROM nodes "
                            + "WHERE symbol_id='sample.LombokUsageService#inheritedBuild()') "
                            + "AND external_target_fqn='sample.ChildModel#builder()'"));
            assertEquals("sample.ChildModel#child", scalarString(statement,
                    "SELECT json_extract(metadata,'$.lombok_usage.steps[1].field_id') FROM edges "
                            + "WHERE source_id IN (SELECT id FROM nodes "
                            + "WHERE symbol_id='sample.LombokUsageService#inheritedBuild()') "
                            + "AND external_target_fqn='sample.ChildModel#builder()'"));
            assertEquals("toBuilder", scalarString(statement,
                    "SELECT json_extract(metadata,'$.lombok_usage.root') FROM edges "
                            + "WHERE source_id IN (SELECT id FROM nodes "
                            + "WHERE symbol_id='sample.LombokUsageService#copyBuild(sample.ChildModel)') "
                            + "AND external_target_fqn='sample.ChildModel#toBuilder()'"));
        }

        try (QueryService query = new QueryService(db)) {
            var getter = query.search("getName", "METHOD", 10).stream()
                    .filter(row -> "sample.User#getName".equals(row.qualifiedName))
                    .findFirst().orElseThrow();
            assertEquals("lombok-ast", getter.producerId);
            assertEquals("lombok", getter.syntheticOrigin.get("generator"));
            assertEquals("ast", getter.syntheticOrigin.get("generator_mode"));
            assertEquals(false, getter.syntheticOrigin.get("body_available"));
            var user = query.searchByName("User", "CLASS", 20).stream()
                    .filter(row -> "sample.User".equals(row.qualifiedName)).findFirst().orElseThrow();
            assertEquals("complete", user.lombok.get("coverage"));
            assertTrue(((List<?>) user.lombok.get("modeled_capabilities")).contains("getter"));
            var context = query.context("sample.User", 0);
            assertEquals(user.lombok, context.node.lombok);
            var name = context.members.stream().filter(row -> "sample.User#name".equals(row.qualifiedName))
                    .findFirst().orElseThrow();
            assertEquals(List.of("NonNull"), name.lombok.get("detected_annotations"));

            var builderCall = query.calleesOf("sample.LombokUsageService#build()", 1).stream()
                    .filter(edge -> "sample.UnsupportedBuilder#builder()"
                            .equals(edge.externalTargetFqn))
                    .findFirst().orElseThrow();
            assertTrue(builderCall.metadata.contains("\"status\":\"usage-observed\""));
            assertTrue(query.callersOf("sample.UnsupportedBuilder#builder()", 1).stream()
                    .anyMatch(edge -> "sample.LombokUsageService#build()".equals(edge.sourceSymbolId)));

            Map<String, Object> golden = new LinkedHashMap<>();
            golden.put("search_user", user.lombok);
            golden.put("context_accessor_user", query.context("sample.AccessorUser", 0).node.lombok);
            assertCapabilityGolden(golden, db);
        }

        var declarations = runCli("declarations-of", "--file", "src/main/java/sample/User.java",
                "--format", "json", "--index", db.toString());
        assertEquals(0, declarations.exitCode(), declarations.stderr());
        Map<?, ?> envelope = (Map<?, ?>) Json.parseTree(declarations.stdout());
        @SuppressWarnings("unchecked")
        List<Map<?, ?>> rows = (List<Map<?, ?>>) envelope.get("results");
        Map<?, ?> type = rows.stream().filter(row -> "sample.User".equals(row.get("symbol_id")))
                .findFirst().orElseThrow();
        assertEquals("complete", ((Map<?, ?>) type.get("lombok")).get("coverage"));
    }

    @Test
    void incrementalReplacesGeneratedMembersAndConfigChangeForcesFull(@TempDir Path tmp) throws Exception {
        Path project = copyFixture(tmp);
        Path db = tmp.resolve("incremental.db");
        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--java-version", "25", "--lombok", "ast",
                "--output", db.toString(), "--format", "json");

        Path user = project.resolve("src/main/java/sample/User.java");
        Files.writeString(user, Files.readString(user)
                .replace("import lombok.Data;", "import lombok.Value;")
                .replace("@Data", "@Value"));
        var incremental = CliTestSupport.runIndex(project,
                "--no-classpath", "--java-version", "25", "--lombok", "ast", "--incremental",
                "--output", db.toString(), "--format", "json");
        assertEquals(0, incremental.exitCode(), incremental.stderr());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            assertEquals(0, scalar(statement, "SELECT count(*) FROM nodes "
                    + "WHERE symbol_id='sample.User#setName(java.lang.String)'"));
            assertEquals(1, scalar(statement, "SELECT count(*) FROM nodes "
                    + "WHERE symbol_id='sample.User#getName()' AND producer_id='lombok-ast'"));
            assertEquals("partial", scalarString(statement,
                    "SELECT json_extract(metadata,'$.lombok.coverage') FROM nodes "
                            + "WHERE symbol_id='sample.User'"));
        }

        Files.writeString(user, Files.readString(user)
                .replace("import lombok.Value;", "import lombok.Value;\nimport lombok.experimental.Accessors;")
                .replace("@Value", "@Value @Accessors(fluent = true)"));
        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--java-version", "25", "--lombok", "ast", "--incremental",
                "--output", db.toString(), "--format", "json");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            assertEquals(0, scalar(statement, "SELECT count(*) FROM nodes "
                    + "WHERE symbol_id='sample.User#getName()'"));
            assertEquals("partial", scalarString(statement,
                    "SELECT json_extract(metadata,'$.lombok.coverage') FROM nodes "
                            + "WHERE symbol_id='sample.User'"));
        }

        Files.writeString(user, Files.readString(user)
                .replace("\nimport lombok.experimental.Accessors;", "")
                .replace(" @Accessors(fluent = true)", ""));
        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--java-version", "25", "--lombok", "ast", "--incremental",
                "--output", db.toString(), "--format", "json");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            assertEquals(1, scalar(statement, "SELECT count(*) FROM nodes "
                    + "WHERE symbol_id='sample.User#getName()' AND producer_id='lombok-ast'"));
        }

        Path builder = project.resolve("src/main/java/sample/UnsupportedBuilder.java");
        Files.writeString(builder, "package sample; public class UnsupportedBuilder { private String value; }\n");
        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--java-version", "25", "--lombok", "ast", "--incremental",
                "--output", db.toString(), "--format", "json");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            assertNull(scalarString(statement,
                    "SELECT json_extract(metadata,'$.lombok') FROM nodes "
                            + "WHERE symbol_id='sample.UnsupportedBuilder'"));
            assertEquals(0, scalar(statement, "SELECT count(*) FROM index_diagnostics "
                    + "WHERE source_file='src/main/java/sample/UnsupportedBuilder.java' "
                    + "AND code='LOMBOK_FEATURE_UNSUPPORTED'"));
            assertEquals(0, scalar(statement, "SELECT count(*) FROM edges "
                    + "WHERE source_id IN (SELECT id FROM nodes "
                    + "WHERE symbol_id='sample.LombokUsageService#build()') "
                    + "AND json_extract(metadata,'$.lombok_usage') IS NOT NULL"));
        }

        Files.writeString(project.resolve("lombok.config"), "lombok.getter.noIsPrefix = true\n");
        var configChanged = CliTestSupport.runIndex(project,
                "--no-classpath", "--java-version", "25", "--lombok", "ast", "--incremental",
                "--output", db.toString(), "--format", "json");
        assertEquals(0, configChanged.exitCode(), configChanged.stderr());
        assertTrue(configChanged.stderr().contains("extension fingerprint changed"),
                configChanged.stderr());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            assertEquals(1, scalar(statement, "SELECT count(*) FROM nodes "
                    + "WHERE symbol_id='sample.User#getActive()'"));
            assertEquals(0, scalar(statement, "SELECT count(*) FROM nodes "
                    + "WHERE symbol_id='sample.User#isActive()'"));
        }
    }

    private static Path copyFixture(Path tmp) throws IOException {
        Path source = Path.of(System.getProperty("user.dir"), "fixtures", "lombok-sample");
        Path target = tmp.resolve("lombok-sample");
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
        return target;
    }

    private static CliTestSupport.RunResult runCli(String... args) throws Exception {
        return CliTestSupport.capture(() -> new CommandLine(new AnatomistCli()).execute(args));
    }

    private static void assertCapabilityGolden(Map<String, Object> actual, Path db) throws Exception {
        var declarations = runCli("declarations-of", "--file",
                "src/main/java/sample/UnsupportedBuilder.java", "--format", "json",
                "--index", db.toString());
        assertEquals(0, declarations.exitCode(), declarations.stderr());
        Map<?, ?> envelope = (Map<?, ?>) Json.parseTree(declarations.stdout());
        @SuppressWarnings("unchecked")
        List<Map<?, ?>> rows = (List<Map<?, ?>>) envelope.get("results");
        Map<?, ?> type = rows.stream()
                .filter(row -> "sample.UnsupportedBuilder".equals(row.get("symbol_id")))
                .findFirst().orElseThrow();
        actual.put("declarations_builder", type.get("lombok"));

        Path expectedPath = Path.of(System.getProperty("user.dir"), "src", "test", "resources",
                "golden", "lombok-capabilities.json");
        String expected = Json.writeCanonical(Json.parseTree(Files.readString(expectedPath)));
        assertEquals(expected, Json.writeCanonical(actual));
    }

    private static int scalar(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static String scalarString(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
