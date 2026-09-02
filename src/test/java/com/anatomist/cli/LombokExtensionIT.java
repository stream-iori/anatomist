package com.anatomist.cli;

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
        assertTrue(Files.isRegularFile(strictDb), "strict gate evaluates the committed index");
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
                            + "WHERE code='LOMBOK_FEATURE_UNSUPPORTED' LIMIT 1"));
            assertEquals("ast", scalarString(statement,
                    "SELECT value FROM project_meta WHERE key='lombok_mode'"));
            assertEquals("partial", scalarString(statement,
                    "SELECT value FROM project_meta WHERE key='lombok_coverage'"));
            assertTrue(Long.parseLong(scalarString(statement,
                    "SELECT value FROM project_meta WHERE key='lombok_generated_members'")) > 0);
        }

        try (QueryService query = new QueryService(db)) {
            var getter = query.search("getName", "METHOD", 10).stream()
                    .filter(row -> "sample.User#getName".equals(row.qualifiedName))
                    .findFirst().orElseThrow();
            assertEquals("lombok-ast", getter.producerId);
            assertEquals("lombok", getter.syntheticOrigin.get("generator"));
            assertEquals("ast", getter.syntheticOrigin.get("generator_mode"));
            assertEquals(false, getter.syntheticOrigin.get("body_available"));
        }
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
