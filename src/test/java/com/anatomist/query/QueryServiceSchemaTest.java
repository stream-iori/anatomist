package com.anatomist.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;

import com.anatomist.store.SqliteStore;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryServiceSchemaTest {

    @Test
    void rejectsPreV4IndexWithoutCompatibilityFallback(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("v3.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=3");
        }

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new QueryService(db));
        assertTrue(error.getMessage().contains("SCHEMA_MISMATCH"));
        assertTrue(error.getMessage().contains("re-index required"));
    }

    @Test
    void connectionIsDatabaseEnforcedReadOnly(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("readonly.db");
        try (SqliteStore store = new SqliteStore(db)) { store.initSchema(); }
        try (QueryService service = new QueryService(db)) {
            assertThrows(SQLException.class, () -> service.connection().createStatement()
                    .executeUpdate("INSERT INTO project_meta(key,value) VALUES ('write','forbidden')"));
        }
    }
}
