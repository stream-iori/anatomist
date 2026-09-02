package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.test.CliTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexReplacementIT {

    @Test
    void semanticsMismatchRecreatesAndDropsRenewableContent(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Path database = tmp.resolve("index.db");
        indexOk(project, database);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO documents(path,title,content,doc_type) "
                    + "VALUES ('notes.md','Notes','renewable','markdown')");
            statement.executeUpdate("INSERT INTO semantic_annotations(category,source,confidence) "
                    + "VALUES ('domain','LLM','HIGH')");
            statement.executeUpdate("DELETE FROM project_meta "
                    + "WHERE key='graph_semantics_version'");
        }

        var rebuilt = CliTestSupport.runIndex(project, "--incremental", "--no-classpath",
                "--output", database.toString(), "--format", "json");
        assertEquals(0, rebuilt.exitCode(), rebuilt.stderr());
        Map<?, ?> rebuild = (Map<?, ?>) object(rebuilt.stdout()).get("rebuild");
        assertEquals("recreate", rebuild.get("action"));
        assertEquals(List.of("GRAPH_SEMANTICS_MISMATCH"), rebuild.get("reasons"));
        assertEquals(1, number(rebuild, "discarded_documents"));
        assertEquals(1, number(rebuild, "discarded_semantic_annotations"));
        assertEquals(0, scalar(database, "SELECT count(*) FROM documents"));
        assertEquals(0, scalar(database, "SELECT count(*) FROM semantic_annotations"));
        assertEquals(1, scalar(database, "SELECT CAST(value AS INTEGER) FROM project_meta "
                + "WHERE key='graph_semantics_version'"));
    }

    @Test
    void failedFullBuildLeavesLastGoodIndexReadable(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Path database = tmp.resolve("index.db");
        indexOk(project, database);
        long originalNodes = scalar(database, "SELECT count(*) FROM nodes");

        Files.writeString(project.resolve("src/main/java/p/A.java"),
                "package p; class A { void broken( }\n", StandardCharsets.UTF_8);
        var failed = CliTestSupport.runIndex(project, "--full", "--strict-health",
                "--no-classpath", "--output", database.toString(), "--format", "json");
        assertEquals(3, failed.exitCode(), failed.stderr());
        Map<?, ?> output = object(failed.stdout());
        Map<?, ?> rebuild = (Map<?, ?>) output.get("rebuild");
        assertEquals(Boolean.FALSE, rebuild.get("published"));
        assertTrue(Files.isRegularFile(database));
        assertEquals(originalNodes, scalar(database, "SELECT count(*) FROM nodes"));
        assertFalse(Files.exists(database.resolveSibling(database.getFileName() + "-wal")));
    }

    private static void indexOk(Path project, Path database) throws Exception {
        var result = CliTestSupport.runIndex(project, "--no-classpath",
                "--output", database.toString(), "--format", "json");
        assertEquals(0, result.exitCode(), result.stderr());
    }

    private static Map<?, ?> object(String json) {
        return (Map<?, ?>) Json.parseTree(json);
    }

    private static int number(Map<?, ?> map, String key) {
        return ((Number) map.get(key)).intValue();
    }

    private static long scalar(Path database, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var row = statement.executeQuery(sql)) {
            return row.next() ? row.getLong(1) : 0;
        }
    }
}
