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

class EnrichArchRoleTest {

    static Path dbPath;
    static SqliteStore store;

    @BeforeAll
    static void setUp(@TempDir Path tmp) {
        dbPath = tmp.resolve("enrich-role.db");
        store = new SqliteStore(dbPath);
        store.initSchema();

        ExtractionResult r = new ExtractionResult();
        Node n = new Node();
        n.id = "com.x.OrderService"; n.label = "OrderService"; n.kind = "CLASS";
        n.qualifiedName = "com.x.OrderService"; n.sourceFile = "OrderService.java";
        n.pkg = "com.x"; n.scope = "MAIN";
        r.nodes.add(n);
        store.write(r);

        store.upsertArchRoles(List.of(
                new ArchRole("com.x.OrderService", "APPLICATION", "auto_call_pattern", "call pattern")
        ));
    }

    @AfterAll
    static void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void enrichNode_includesArchRole() {
        try (QueryService q = new QueryService(dbPath)) {
            EnrichResult r = q.enrichNode("com.x.OrderService", 0, false);
            assertNotNull(r);
            assertNotNull(r.archRole);
            assertEquals("APPLICATION", r.archRole.role);
            assertEquals("auto_call_pattern", r.archRole.confidence);
        }
    }

    @Test
    void enrichNode_nullWhenNoArchRole() {
        // Create a node without arch_role
        try (SqliteStore s2 = new SqliteStore(dbPath)) {
            ExtractionResult r2 = new ExtractionResult();
            Node n2 = new Node();
            n2.id = "com.x.Util"; n2.label = "Util"; n2.kind = "CLASS";
            n2.qualifiedName = "com.x.Util"; n2.sourceFile = "Util.java";
            n2.pkg = "com.x"; n2.scope = "MAIN";
            r2.nodes.add(n2);
            s2.write(r2);
        }
        try (QueryService q = new QueryService(dbPath)) {
            EnrichResult r = q.enrichNode("com.x.Util", 0, false);
            assertNotNull(r);
            assertNull(r.archRole);
        }
    }
}
