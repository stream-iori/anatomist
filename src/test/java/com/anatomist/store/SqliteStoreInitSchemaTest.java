package com.anatomist.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SqliteStoreInitSchemaTest {

    private SqliteStore store;

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void initSchema_createsExpectedTablesAndIndexes(@TempDir Path tmp) throws Exception {
        store = new SqliteStore(tmp.resolve("index.db"));
        store.initSchema();

        Set<String> tables = listObjects(store.connection(), "table");
        assertTrue(tables.contains("nodes"), "missing table nodes; got " + tables);
        assertTrue(tables.contains("edges"));
        assertTrue(tables.contains("annotations"));
        assertTrue(tables.contains("node_names"));

        Set<String> indexes = listObjects(store.connection(), "index");
        assertTrue(indexes.contains("idx_nodes_kind"), "missing idx_nodes_kind; got " + indexes);
        assertTrue(indexes.contains("idx_edges_source_id"));
        assertTrue(indexes.contains("idx_annotations_fqn"));
    }

    @Test
    void initSchema_createsFts5Triggers(@TempDir Path tmp) throws Exception {
        store = new SqliteStore(tmp.resolve("index.db"));
        store.initSchema();

        Connection c = store.connection();
        try (Statement st = c.createStatement()) {
            st.executeUpdate(
                "INSERT INTO nodes(id, symbol_id, label, kind, qualified_name, source_file, module, scope) " +
                "VALUES ('.::MAIN::com.x.A', 'com.x.A', 'A', 'CLASS', 'com.x.A', 'A.java', '.', 'MAIN')"
            );
        }

        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM node_names WHERE node_names MATCH 'A'")) {
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) >= 1, "FTS5 trigger did not sync new row");
        }
    }

    @Test
    void initSchema_writesUserVersion(@TempDir Path tmp) throws Exception {
        store = new SqliteStore(tmp.resolve("index.db"));
        store.initSchema();

        try (Statement st = store.connection().createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            assertTrue(rs.next());
            assertEquals(IndexSchema.VERSION, rs.getInt(1));
        }
    }

    private static Set<String> listObjects(Connection c, String type) throws Exception {
        Set<String> out = new HashSet<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='" + type + "'")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }
}
