package com.anatomist.query;

import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.anatomist.store.SqliteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the single source of truth for edge/node SELECT column lists and their
 * ResultSet → row mapping. Before this extraction the 13-column edge list and
 * its positional mapping were copy-pasted across 6+ query methods; adding
 * {@code e.context} forced shotgun edits. These tests lock the shared shape.
 */
class RowMappersTest {

    private Path freshDb(Path tmp) {
        Path db = tmp.resolve("rm.db");
        try (SqliteStore store = new SqliteStore(db)) {
            store.initSchema();
            ExtractionResult r = new ExtractionResult();
            Node a = new Node();
            a.id = "p.A"; a.label = "A"; a.kind = "CLASS"; a.qualifiedName = "p.A";
            a.pkg = "p"; a.sourceFile = "p/A.java"; a.sourceLocation = "L1"; a.scope = "MAIN";
            Node m = new Node();
            m.id = "p.A#run()"; m.label = "run"; m.kind = "METHOD"; m.qualifiedName = "p.A#run";
            m.pkg = "p"; m.sourceFile = "p/A.java"; m.sourceLocation = "L2"; m.scope = "MAIN";
            Node t = new Node();
            t.id = "p.B#foo()"; t.label = "foo"; t.kind = "METHOD"; t.qualifiedName = "p.B#foo";
            t.pkg = "p"; t.sourceFile = "p/B.java"; t.sourceLocation = "L1"; t.scope = "MAIN";
            r.nodes.add(a); r.nodes.add(m); r.nodes.add(t);
            Edge e = new Edge();
            e.sourceId = "p.A#run()"; e.targetId = "p.B#foo()"; e.relation = "CALLS";
            e.callKind = "INSTANCE"; e.isExternal = false; e.context = "loop";
            e.sourceFile = "p/A.java"; e.sourceLocation = "L2";
            r.edges.add(e);
            store.write(r);
        }
        return db;
    }

    @Test
    void edgeColsFlatAndMapperRoundTrip(@TempDir Path tmp) throws Exception {
        Path db = freshDb(tmp);
        String sql = "SELECT " + RowMappers.edgeColsFlat("1")
                + RowMappers.EDGE_FROM_JOINS
                + " WHERE e.relation = 'CALLS'";
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            EdgeRow row = RowMappers.mapEdge(rs);
            assertEquals("p.A#run()", row.source);
            assertEquals("p.B#foo()", row.target);
            assertEquals("CALLS", row.relation);
            assertEquals("INSTANCE", row.callKind);
            assertFalse(row.isExternal);
            assertEquals(1, row.depth);
            assertEquals("run", row.sourceLabel);
            assertEquals("foo", row.targetLabel);
            assertEquals("p.B#foo", row.targetQualifiedName);
            assertEquals("loop", row.context);
        }
    }

    @Test
    void nodeColsAndMapperRoundTrip(@TempDir Path tmp) throws Exception {
        Path db = freshDb(tmp);
        String sql = "SELECT " + RowMappers.NODE_COLS + " FROM nodes n WHERE n.id = 'p.A'";
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            NodeRow n = RowMappers.mapNode(rs);
            assertEquals("p.A", n.id);
            assertEquals("A", n.label);
            assertEquals("CLASS", n.kind);
            assertEquals("p.A", n.qualifiedName);
            assertEquals("p/A.java", n.sourceFile);
            assertEquals("L1", n.sourceLocation);
        }
    }

    @Test
    void chainColsMapperParity(@TempDir Path tmp) throws Exception {
        // The recursive-CTE final SELECT projects the same 13 columns (aliased
        // off the chain CTE). Mapper must read them identically by position.
        Path db = freshDb(tmp);
        String sql = "WITH RECURSIVE chain AS ("
                + "  SELECT e.source_id, e.target_id, e.external_target_fqn, e.relation,"
                + "         e.call_kind, e.confidence, e.resolution, e.is_external, e.source_file,"
                + "         e.source_location, e.context, e.metadata, 1 AS depth"
                + "    FROM edges e WHERE e.relation='CALLS'"
                + ") SELECT " + RowMappers.EDGE_COLS_CHAIN
                + "    FROM chain c "
                + "    LEFT JOIN nodes src ON c.source_id = src.id "
                + "    LEFT JOIN nodes tgt ON c.target_id = tgt.id";
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            EdgeRow row = RowMappers.mapEdge(rs);
            assertEquals("p.A#run()", row.source);
            assertEquals("p.B#foo", row.targetQualifiedName);
            assertEquals("loop", row.context);
            assertEquals(1, row.depth);
        }
    }
}
