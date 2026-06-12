package com.anatomist.query;

import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.anatomist.store.SqliteStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaginationTest {

    static Path dbPath;
    static SqliteStore store;

    @BeforeAll
    static void setUp(@TempDir Path tmp) {
        dbPath = tmp.resolve("page.db");
        store = new SqliteStore(dbPath);
        store.initSchema();

        ExtractionResult r = new ExtractionResult();
        // Hub class with many deps
        r.nodes.add(typeNode("com.x.Hub", "Hub"));
        for (int i = 0; i < 10; i++) {
            String id = "com.x.Dep" + i;
            r.nodes.add(typeNode(id, "Dep" + i));
            r.edges.add(refEdge("com.x.Hub", id));
        }
        // Also add a "Payment" dep for filter testing
        r.nodes.add(typeNode("com.x.PaymentClient", "PaymentClient"));
        r.edges.add(refEdge("com.x.Hub", "com.x.PaymentClient"));
        store.write(r);
    }

    @AfterAll
    static void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void depsOf_defaultLimit_truncatesAndReportsTotal() {
        try (QueryService q = new QueryService(dbPath)) {
            PagedResult<EdgeRow> result = q.depsOfPaged("com.x.Hub", 5, 0, null);
            assertEquals(5, result.items().size());
            assertEquals(11, result.total());
            assertTrue(result.truncated());
        }
    }

    @Test
    void depsOf_offset_skipsItems() {
        try (QueryService q = new QueryService(dbPath)) {
            PagedResult<EdgeRow> result = q.depsOfPaged("com.x.Hub", 5, 5, null);
            assertEquals(5, result.items().size());
            assertEquals(11, result.total());
            // offset=5, limit=5 → items 5-9 of 11
            assertTrue(result.truncated());

            PagedResult<EdgeRow> last = q.depsOfPaged("com.x.Hub", 5, 10, null);
            assertEquals(1, last.items().size());
            assertFalse(last.truncated());
        }
    }

    @Test
    void depsOf_filter_matchesSubstring() {
        try (QueryService q = new QueryService(dbPath)) {
            PagedResult<EdgeRow> result = q.depsOfPaged("com.x.Hub", 50, 0, "Payment");
            assertEquals(1, result.items().size());
            assertEquals(1, result.total());
            assertFalse(result.truncated());
            assertTrue(result.items().get(0).targetLabel.contains("Payment")
                    || result.items().get(0).targetQualifiedName.contains("Payment"));
        }
    }

    @Test
    void depsOf_filter_caseInsensitive() {
        try (QueryService q = new QueryService(dbPath)) {
            PagedResult<EdgeRow> result = q.depsOfPaged("com.x.Hub", 50, 0, "payment");
            assertEquals(1, result.items().size());
        }
    }

    @Test
    void depsOf_filter_noMatch() {
        try (QueryService q = new QueryService(dbPath)) {
            PagedResult<EdgeRow> result = q.depsOfPaged("com.x.Hub", 50, 0, "NonExistent");
            assertTrue(result.items().isEmpty());
            assertEquals(0, result.total());
        }
    }

    @Test
    void usedBy_paged_works() {
        try (QueryService q = new QueryService(dbPath)) {
            PagedResult<EdgeRow> result = q.usedByPaged("com.x.Dep0", 50, 0, null);
            assertEquals(1, result.items().size());
            assertEquals(1, result.total());
        }
    }

    private static Node typeNode(String id, String label) {
        Node n = new Node();
        n.id = id; n.label = label; n.kind = "CLASS";
        n.qualifiedName = id; n.sourceFile = label + ".java";
        n.pkg = "com.x"; n.scope = "MAIN";
        return n;
    }

    private static Edge refEdge(String src, String tgt) {
        Edge e = new Edge();
        e.sourceId = src; e.targetId = tgt;
        e.relation = "REFERENCES"; e.isExternal = false;
        return e;
    }
}
