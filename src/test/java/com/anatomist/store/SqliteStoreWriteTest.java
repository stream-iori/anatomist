package com.anatomist.store;

import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class SqliteStoreWriteTest {

    private SqliteStore store;

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void write_persistsNodesAndEdges(@TempDir Path tmp) throws Exception {
        store = new SqliteStore(tmp.resolve("index.db"));
        store.initSchema();

        ExtractionResult r = new ExtractionResult();
        r.nodes.add(node("com.x.Widget", "Widget", "CLASS"));
        r.nodes.add(node("com.x.Widget#foo()", "foo", "METHOD"));
        r.edges.add(containsEdge("com.x.Widget", "com.x.Widget#foo()"));

        store.write(r);

        Connection c = store.connection();
        assertEquals(2, count(c, "SELECT count(*) FROM nodes"));
        assertEquals(1, count(c, "SELECT count(*) FROM edges WHERE relation='CONTAINS'"));
        assertEquals(1, count(c, "SELECT count(*) FROM node_names WHERE label MATCH 'Widget'"));
    }

    @Test
    void write_isAtomic(@TempDir Path tmp) throws Exception {
        store = new SqliteStore(tmp.resolve("index.db"));
        store.initSchema();

        ExtractionResult r = new ExtractionResult();
        r.nodes.add(node("com.x.A", "A", "CLASS"));
        // CHECK violation: is_external=0 but target_id null
        Edge bad = new Edge();
        bad.sourceId = "com.x.A";
        bad.targetId = null;
        bad.externalTargetFqn = null;
        bad.relation = "CONTAINS";
        bad.isExternal = false;
        r.edges.add(bad);

        assertThrows(RuntimeException.class, () -> store.write(r));

        Connection c = store.connection();
        assertEquals(0, count(c, "SELECT count(*) FROM nodes"));
        assertEquals(0, count(c, "SELECT count(*) FROM edges"));
    }

    @Test
    void write_supportsIdempotentRewrite(@TempDir Path tmp) throws Exception {
        store = new SqliteStore(tmp.resolve("index.db"));
        store.initSchema();

        ExtractionResult r = new ExtractionResult();
        r.nodes.add(node("com.x.A", "A", "CLASS"));

        store.write(r);
        store.write(r);

        Connection c = store.connection();
        assertEquals(1, count(c, "SELECT count(*) FROM nodes"));
    }

    private static Node node(String id, String label, String kind) {
        Node n = new Node();
        n.id = id;
        n.label = label;
        n.kind = kind;
        n.qualifiedName = id;
        n.sourceFile = "X.java";
        n.scope = "MAIN";
        return n;
    }

    private static Edge containsEdge(String src, String tgt) {
        Edge e = new Edge();
        e.sourceId = src;
        e.targetId = tgt;
        e.relation = "CONTAINS";
        e.isExternal = false;
        e.confidence = "EXTRACTED";
        return e;
    }

    private static int count(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
