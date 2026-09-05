package com.anatomist.query.semantic;

import com.anatomist.store.SqliteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.*;

class SemanticCapabilityRegistryTest {
    @Test
    void derivesCapabilitiesFromSchemaAndMetadata(@TempDir Path tmp) throws Exception {
        try (SqliteStore store = new SqliteStore(tmp.resolve("index.db"))) {
            store.initSchema();
            SemanticCapabilityRegistry registry = new SemanticCapabilityRegistry(store);
            assertTrue(registry.supports("resolve"));
            assertTrue(registry.supports("calls"));
            assertTrue(registry.supports("type-relations"));
            assertFalse(registry.supports("source"));
            assertThrows(SemanticCapabilityRegistry.UnsupportedCapabilityException.class,
                    () -> registry.require("source"));

            try (PreparedStatement statement = store.connection().prepareStatement(
                    "INSERT OR REPLACE INTO project_meta(key,value) VALUES(?,?)")) {
                statement.setString(1, "source_root"); statement.setString(2, tmp.toString());
                statement.executeUpdate();
                statement.setString(1, "source_snapshot_fingerprint");
                statement.setString(2, "sha256:test"); statement.executeUpdate();
            }
            assertTrue(registry.supports("source"));
        }
    }
}
