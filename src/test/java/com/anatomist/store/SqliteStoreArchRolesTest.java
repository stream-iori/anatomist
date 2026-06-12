package com.anatomist.store;

import com.anatomist.model.ArchRole;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SqliteStoreArchRolesTest {

    private SqliteStore store;

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void upsertArchRoles_insertsAndQueries(@TempDir Path tmp) {
        store = setup(tmp);

        store.upsertArchRoles(List.of(
                new ArchRole("com.x.OrderService", "APPLICATION", "auto_annotation", "@Service"),
                new ArchRole("com.x.OrderController", "ENTRY", "auto_annotation", "@RestController")
        ));

        List<ArchRole> all = store.queryArchRoles(null);
        assertEquals(2, all.size());

        List<ArchRole> entries = store.queryArchRoles("ENTRY");
        assertEquals(1, entries.size());
        assertEquals("com.x.OrderController", entries.get(0).nodeId);
    }

    @Test
    void upsertArchRoles_replacesOnConflict(@TempDir Path tmp) {
        store = setup(tmp);

        store.upsertArchRoles(List.of(
                new ArchRole("com.x.OrderService", "APPLICATION", "auto_annotation", "@Service")
        ));
        store.upsertArchRoles(List.of(
                new ArchRole("com.x.OrderService", "DOMAIN_SERVICE", "agent", "agent judgment")
        ));

        Optional<ArchRole> role = store.getArchRole("com.x.OrderService");
        assertTrue(role.isPresent());
        assertEquals("DOMAIN_SERVICE", role.get().role);
        assertEquals("agent", role.get().confidence);
    }

    @Test
    void getArchRole_returnsEmptyForMissing(@TempDir Path tmp) {
        store = setup(tmp);
        assertTrue(store.getArchRole("com.x.Missing").isEmpty());
    }

    @Test
    void archRoles_cascadeDeletesWithNode(@TempDir Path tmp) throws Exception {
        store = setup(tmp);
        store.upsertArchRoles(List.of(
                new ArchRole("com.x.OrderService", "APPLICATION", "auto_annotation", "@Service")
        ));

        try (var st = store.connection().createStatement()) {
            st.executeUpdate("DELETE FROM nodes WHERE id = 'com.x.OrderService'");
        }

        assertTrue(store.getArchRole("com.x.OrderService").isEmpty());
    }

    private static SqliteStore setup(Path tmp) {
        SqliteStore s = new SqliteStore(tmp.resolve("index.db"));
        s.initSchema();
        ExtractionResult r = new ExtractionResult();
        Node n1 = new Node();
        n1.id = "com.x.OrderService"; n1.label = "OrderService"; n1.kind = "CLASS";
        n1.qualifiedName = "com.x.OrderService"; n1.sourceFile = "OrderService.java"; n1.scope = "MAIN";
        Node n2 = new Node();
        n2.id = "com.x.OrderController"; n2.label = "OrderController"; n2.kind = "CLASS";
        n2.qualifiedName = "com.x.OrderController"; n2.sourceFile = "OrderController.java"; n2.scope = "MAIN";
        r.nodes.add(n1);
        r.nodes.add(n2);
        s.write(r);
        return s;
    }
}
