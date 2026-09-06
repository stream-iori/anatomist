package com.anatomist.store;

import com.anatomist.core.GraphSemantics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IndexCompatibilityTest {

    @Test
    void classifiesCurrentLegacySchemaMismatchAndCorruption(@TempDir Path tmp) throws Exception {
        Path current = tmp.resolve("current.db");
        createNonEmptyIndex(current);
        assertEquals(IndexCompatibility.Action.INCREMENTAL,
                IndexCompatibility.inspect(current).action());

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + current);
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM project_meta WHERE key='"
                    + GraphSemantics.META_KEY + "'");
        }
        IndexCompatibility.Report legacy = IndexCompatibility.inspect(current);
        assertEquals(IndexCompatibility.Action.RECREATE, legacy.action());
        assertEquals("GRAPH_SEMANTICS_MISMATCH", legacy.primaryReason());
        assertEquals(0, legacy.graphSemanticsVersion());

        Path oldSchema = tmp.resolve("old-schema.db");
        createNonEmptyIndex(oldSchema);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + oldSchema);
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=24");
        }
        IndexCompatibility.Report schema24 = IndexCompatibility.inspect(oldSchema);
        assertEquals(IndexCompatibility.Action.RECREATE, schema24.action());
        assertEquals("SCHEMA_MISMATCH", schema24.primaryReason());
        assertEquals(24, schema24.schemaVersion());

        Path corrupt = tmp.resolve("corrupt.db");
        Files.writeString(corrupt, "not sqlite", StandardCharsets.UTF_8);
        assertEquals("DATABASE_CORRUPT",
                IndexCompatibility.inspect(corrupt).primaryReason());
    }

    private static void createNonEmptyIndex(Path database) throws Exception {
        try (SqliteStore store = new SqliteStore(database)) {
            store.initSchema();
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO nodes(id,symbol_id,label,kind,qualified_name,source_file,module,scope)
                    VALUES ('p::MAIN::p.A','p.A','A','CLASS','p.A','src/A.java','p','MAIN')
                    """);
            statement.executeUpdate("""
                    INSERT INTO file_cache(source_file,hash,schema_version)
                    VALUES ('src/A.java','abc',1)
                    """);
        }
    }
}
