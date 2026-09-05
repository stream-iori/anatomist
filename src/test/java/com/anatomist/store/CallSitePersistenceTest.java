package com.anatomist.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CallSitePersistenceTest {

    @Test
    void incrementalRefreshLeavesUnrelatedCallerUntouched(@TempDir Path tmp) throws Exception {
        try (SqliteStore store = new SqliteStore(tmp.resolve("index.db"))) {
            store.initSchema();
            Connection c = store.connection();
            insertNode(c, "caller-a", "p.A#run()", "A.java");
            insertNode(c, "caller-b", "p.B#run()", "B.java");
            insertNode(c, "target", "p.Target#go()", "Target.java");
            insertCall(c, "caller-a", "target", "A.java", 3);
            insertCall(c, "caller-b", "target", "B.java", 4);
            CallSitePersistence.rebuild(c);

            try (Statement statement = c.createStatement()) {
                statement.execute("CREATE TABLE deleted_callers(caller_id TEXT)");
                statement.execute("CREATE TRIGGER audit_call_site_delete AFTER DELETE ON call_sites "
                        + "BEGIN INSERT INTO deleted_callers SELECT caller_id FROM call_site_owners "
                        + "WHERE owner_pk=old.owner_pk; END");
            }
            String unrelatedHash = scalar(c,
                    "SELECT hex(cs.stable_hash) FROM call_sites cs JOIN call_site_owners cso "
                            + "ON cso.owner_pk=cs.owner_pk WHERE cso.caller_id='caller-b'");
            CallSitePersistence.AffectedScope scope = CallSitePersistence.captureAffected(
                    c, List.of("A.java"), Set.of());
            try (Statement statement = c.createStatement()) {
                statement.executeUpdate("DELETE FROM edges WHERE source_file='A.java'");
            }
            CallSitePersistence.refresh(c, scope);

            assertEquals(1, count(c, "SELECT count(*) FROM deleted_callers"));
            assertEquals("caller-a", scalar(c, "SELECT caller_id FROM deleted_callers"));
            assertEquals(unrelatedHash, scalar(c,
                    "SELECT hex(cs.stable_hash) FROM call_sites cs JOIN call_site_owners cso "
                            + "ON cso.owner_pk=cs.owner_pk WHERE cso.caller_id='caller-b'"));
            assertEquals(0, count(c, "SELECT count(*) FROM call_sites cs JOIN call_site_owners cso "
                    + "ON cso.owner_pk=cs.owner_pk WHERE cso.caller_id='caller-a'"));
            assertEquals(0, count(c, "SELECT count(*) FROM call_site_targets cst "
                    + "LEFT JOIN call_sites cs ON cs.site_pk=cst.call_site_pk WHERE cs.site_pk IS NULL"));
        }
    }

    @Test
    void queryIndexesCoverOutgoingAndIncomingLookups(@TempDir Path tmp) throws Exception {
        try (SqliteStore store = new SqliteStore(tmp.resolve("index.db"))) {
            store.initSchema();
            Connection c = store.connection();
            assertPlanUses(c,
                    "SELECT cs.site_pk FROM call_site_owners cso JOIN call_sites cs "
                            + "ON cs.owner_pk=cso.owner_pk WHERE cso.caller_id=? "
                            + "ORDER BY cs.begin_line,cs.begin_column,cs.ordinal",
                    "idx_call_sites_caller_order");
            assertPlanUses(c,
                    "SELECT call_site_pk FROM call_site_targets WHERE target_id=?",
                    "idx_call_site_targets_internal");
        }
    }

    private static void insertNode(Connection c, String id, String symbol, String file)
            throws Exception {
        try (PreparedStatement statement = c.prepareStatement(
                "INSERT INTO nodes(id,symbol_id,label,kind,qualified_name,source_file,module,scope) "
                        + "VALUES(?,?,?,?,?,?,'.','MAIN')")) {
            statement.setString(1, id); statement.setString(2, symbol);
            statement.setString(3, id); statement.setString(4, "METHOD");
            statement.setString(5, symbol); statement.setString(6, file);
            statement.executeUpdate();
        }
    }

    private static void insertCall(Connection c, String caller, String target,
                                   String file, int line) throws Exception {
        try (PreparedStatement statement = c.prepareStatement(
                "INSERT INTO edges(source_id,target_id,relation,call_kind,confidence,is_external,"
                        + "source_file,begin_line,begin_column,end_line,end_column,source_ordinal,"
                        + "syntax_target) VALUES(?,?,'CALLS','VIRTUAL','EXTRACTED',0,?,?,1,?,3,0,'go')")) {
            statement.setString(1, caller); statement.setString(2, target);
            statement.setString(3, file); statement.setInt(4, line);
            statement.setInt(5, line); statement.executeUpdate();
        }
    }

    private static void assertPlanUses(Connection c, String sql, String index) throws Exception {
        try (PreparedStatement statement = c.prepareStatement("EXPLAIN QUERY PLAN " + sql)) {
            statement.setString(1, "value");
            try (ResultSet rows = statement.executeQuery()) {
                StringBuilder plan = new StringBuilder();
                while (rows.next()) plan.append(rows.getString("detail")).append('\n');
                assertTrue(plan.toString().contains(index), plan.toString());
            }
        }
    }

    private static int count(Connection c, String sql) throws Exception {
        return Integer.parseInt(scalar(c, sql));
    }

    private static String scalar(Connection c, String sql) throws Exception {
        try (Statement statement = c.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), sql);
            return rows.getString(1);
        }
    }
}
