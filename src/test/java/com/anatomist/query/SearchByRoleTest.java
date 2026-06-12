package com.anatomist.query;

import com.anatomist.model.ArchRole;
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

class SearchByRoleTest {

    static Path dbPath;
    static SqliteStore store;

    @BeforeAll
    static void setUp(@TempDir Path tmp) {
        dbPath = tmp.resolve("search.db");
        store = new SqliteStore(dbPath);
        store.initSchema();

        ExtractionResult r = new ExtractionResult();
        r.nodes.add(typeNode("com.x.OrderController", "OrderController"));
        r.nodes.add(typeNode("com.x.OrderService", "OrderService"));
        r.nodes.add(typeNode("com.x.PaymentService", "PaymentService"));
        r.nodes.add(typeNode("com.x.OrderRepo", "OrderRepo"));
        store.write(r);

        store.upsertArchRoles(List.of(
                new ArchRole("com.x.OrderController", "ENTRY", "auto_annotation", "@RestController"),
                new ArchRole("com.x.OrderService", "APPLICATION", "auto_call_pattern", "call pattern"),
                new ArchRole("com.x.PaymentService", "ADAPTER", "auto_call_pattern", "calls HTTP"),
                new ArchRole("com.x.OrderRepo", "REPOSITORY", "auto_annotation", "@Repository")
        ));
    }

    @AfterAll
    static void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void searchByRole_findsMatchingNodes() {
        try (QueryService q = new QueryService(dbPath)) {
            List<NodeRow> results = q.searchByRole("ENTRY", 20);
            assertEquals(1, results.size());
            assertEquals("com.x.OrderController", results.get(0).id);
        }
    }

    @Test
    void searchByRole_multipleResults() {
        try (QueryService q = new QueryService(dbPath)) {
            List<NodeRow> results = q.searchByRole("APPLICATION", 20);
            assertEquals(1, results.size());
            assertEquals("com.x.OrderService", results.get(0).id);
        }
    }

    @Test
    void searchByRole_noMatch() {
        try (QueryService q = new QueryService(dbPath)) {
            List<NodeRow> results = q.searchByRole("DOMAIN_MODEL", 20);
            assertTrue(results.isEmpty());
        }
    }

    private static Node typeNode(String id, String label) {
        Node n = new Node();
        n.id = id; n.label = label; n.kind = "CLASS";
        n.qualifiedName = id; n.sourceFile = label + ".java";
        n.pkg = "com.x"; n.scope = "MAIN";
        return n;
    }
}
