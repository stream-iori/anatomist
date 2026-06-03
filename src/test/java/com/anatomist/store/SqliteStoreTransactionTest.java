package com.anatomist.store;

import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the {@code inTransaction} template introduced to collapse the repeated
 * autoCommit/commit/rollback/restore boilerplate: it must commit on success,
 * roll back on failure, and always restore the prior autoCommit flag.
 */
class SqliteStoreTransactionTest {

    @Test
    void commitsOnSuccessAndRestoresAutoCommit(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("tx.db");
        try (SqliteStore store = new SqliteStore(db)) {
            store.initSchema();
            Connection c = store.connection();
            assertTrue(c.getAutoCommit(), "precondition: autoCommit on");

            store.inTransaction(conn -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("INSERT INTO project_meta(key,value) VALUES ('k','v')");
                }
            });

            assertTrue(c.getAutoCommit(), "autoCommit must be restored after tx");
            assertEquals("v", store.readProjectMeta("k").orElse(null));
        }
    }

    @Test
    void rollsBackOnFailureLeavingNoPartialWrite(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("tx.db");
        try (SqliteStore store = new SqliteStore(db)) {
            store.initSchema();
            Connection c = store.connection();

            RuntimeException boom = assertThrows(RuntimeException.class, () ->
                    store.inTransaction(conn -> {
                        try (Statement st = conn.createStatement()) {
                            st.execute("INSERT INTO project_meta(key,value) VALUES ('a','1')");
                        }
                        // Force a failure after a successful statement.
                        try (Statement st = conn.createStatement()) {
                            st.execute("INSERT INTO no_such_table(x) VALUES (1)");
                        }
                    }));
            assertNotNull(boom);
            assertTrue(c.getAutoCommit(), "autoCommit must be restored after rollback");
            assertTrue(store.readProjectMeta("a").isEmpty(),
                    "partial write must be rolled back");
        }
    }

    @Test
    void writeStillAtomicViaTemplate(@TempDir Path tmp) {
        // Behavior parity: a normal write commits through the template.
        Path db = tmp.resolve("w.db");
        try (SqliteStore store = new SqliteStore(db)) {
            store.initSchema();
            ExtractionResult r = new ExtractionResult();
            Node n = new Node();
            n.id = "p.A"; n.label = "A"; n.kind = "CLASS"; n.qualifiedName = "p.A";
            n.pkg = "p"; n.sourceFile = "p/A.java"; n.sourceLocation = "L1"; n.scope = "MAIN";
            r.nodes.add(n);
            Edge e = new Edge();
            e.sourceId = "p.A"; e.targetId = "p.A"; e.relation = "CONTAINS"; e.isExternal = false;
            e.sourceFile = "p/A.java"; e.sourceLocation = "L1";
            r.edges.add(e);
            store.write(r);
            assertTrue(store.allNodeIds().contains("p.A"));
        }
    }

    @Test
    void writeRollsBackWholeBatchOnConstraintViolation(@TempDir Path tmp) {
        // A dangling internal edge violates the CHECK/FK → whole tx rolls back.
        Path db = tmp.resolve("bad.db");
        try (SqliteStore store = new SqliteStore(db)) {
            store.initSchema();
            ExtractionResult r = new ExtractionResult();
            Node n = new Node();
            n.id = "p.A"; n.label = "A"; n.kind = "CLASS"; n.qualifiedName = "p.A";
            n.pkg = "p"; n.sourceFile = "p/A.java"; n.sourceLocation = "L1"; n.scope = "MAIN";
            r.nodes.add(n);
            Edge bad = new Edge();
            bad.sourceId = "p.A"; bad.targetId = "p.MISSING"; bad.relation = "CALLS";
            bad.isExternal = false; bad.sourceFile = "p/A.java"; bad.sourceLocation = "L1";
            r.edges.add(bad);
            assertThrows(RuntimeException.class, () -> store.write(r));
            assertFalse(store.allNodeIds().contains("p.A"),
                    "node insert must roll back with the failing edge");
        }
    }
}
