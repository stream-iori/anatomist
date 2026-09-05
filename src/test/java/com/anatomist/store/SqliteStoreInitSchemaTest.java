package com.anatomist.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
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
        assertTrue(tables.contains("declarations"));
        assertTrue(tables.contains("call_site_owners"));
        assertTrue(tables.contains("call_sites"));
        assertTrue(tables.contains("call_site_targets"));
        assertTrue(tables.contains("node_names"));
        for (String removed : List.of(
                "flow_nodes", "flow_edges", "method_flow_summaries", "method_flow_coverage")) {
            assertFalse(tables.contains(removed), "removed dataflow table remains: " + removed);
        }

        Set<String> indexes = listObjects(store.connection(), "index");
        assertTrue(indexes.contains("idx_nodes_kind"), "missing idx_nodes_kind; got " + indexes);
        assertTrue(indexes.contains("idx_edges_source_relation_external"));
        assertTrue(indexes.contains("idx_annotations_fqn"));
        assertTrue(indexes.contains("idx_declarations_file"));
        assertTrue(indexes.contains("idx_call_sites_caller_order"));
        assertTrue(indexes.contains("idx_call_site_owners_source"));
        assertTrue(indexes.contains("idx_call_site_targets_identity"));
        assertFalse(indexes.contains("idx_call_sites_context"));
        assertFalse(indexes.contains("idx_edges_call_kind"));
        assertFalse(indexes.contains("idx_edges_source_id"));
        assertFalse(indexes.contains("idx_edges_source_relation"));
        assertFalse(indexes.contains("idx_edges_target_id"));
        assertFalse(indexes.contains("idx_edges_relation"));
        assertFalse(indexes.contains("idx_call_site_targets_site"),
                "site_pk prefix is already covered by the identity index");

        Set<String> callSiteColumns = tableColumns(store.connection(), "call_sites");
        assertTrue(callSiteColumns.contains("site_pk"));
        assertTrue(callSiteColumns.contains("stable_hash"));
        assertTrue(callSiteColumns.contains("owner_pk"));
        assertFalse(callSiteColumns.contains("caller_id"));
        assertFalse(callSiteColumns.contains("source_file"));
        assertFalse(callSiteColumns.contains("id"));
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

    @Test
    void splitSqlStatements_doesNotTreatRangeColumnsAsTriggerBegin() {
        List<String> statements = SchemaManager.splitSqlStatements("""
                CREATE TABLE declarations (
                    begin_line INTEGER,
                    end_line INTEGER,
                    CHECK (end_line >= begin_line)
                );
                CREATE TABLE after_ranges (id INTEGER);
                """);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("CREATE TABLE declarations"));
        assertTrue(statements.get(1).contains("CREATE TABLE after_ranges"));
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

    private static Set<String> tableColumns(Connection c, String table) throws Exception {
        Set<String> out = new HashSet<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) out.add(rs.getString("name"));
        }
        return out;
    }
}
