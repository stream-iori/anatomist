package com.anatomist.query;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CallChainSlicerTest {

    static Connection conn;

    @BeforeAll
    static void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE nodes (id TEXT PRIMARY KEY, label TEXT NOT NULL, "
                    + "kind TEXT NOT NULL, qualified_name TEXT NOT NULL, package TEXT, "
                    + "source_file TEXT NOT NULL, source_location TEXT, module TEXT, "
                    + "scope TEXT NOT NULL DEFAULT 'MAIN', javadoc TEXT, metadata TEXT)");
            s.execute("CREATE TABLE edges (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "source_id TEXT NOT NULL, target_id TEXT, external_target_fqn TEXT, "
                    + "relation TEXT NOT NULL, call_kind TEXT, "
                    + "confidence TEXT NOT NULL DEFAULT 'EXTRACTED', context TEXT, "
                    + "is_external INTEGER NOT NULL DEFAULT 0, source_file TEXT, "
                    + "source_location TEXT, metadata TEXT)");
            s.execute("CREATE TABLE annotations (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "node_id TEXT NOT NULL, annotation_fqn TEXT NOT NULL, attributes TEXT)");
            s.execute("CREATE TABLE arch_roles (node_id TEXT PRIMARY KEY, "
                    + "role TEXT NOT NULL, confidence TEXT NOT NULL, source TEXT NOT NULL)");

            // Types
            s.execute("INSERT INTO nodes VALUES "
                    + "('com.example.ctrl.OrderController','OrderController','CLASS',"
                    + "'com.example.ctrl.OrderController','com.example.ctrl','Ctrl.java',NULL,NULL,'MAIN',NULL,NULL)");
            s.execute("INSERT INTO nodes VALUES "
                    + "('com.example.svc.OrderService','OrderService','CLASS',"
                    + "'com.example.svc.OrderService','com.example.svc','Svc.java',NULL,NULL,'MAIN',NULL,NULL)");
            s.execute("INSERT INTO nodes VALUES "
                    + "('com.example.repo.OrderRepo','OrderRepo','CLASS',"
                    + "'com.example.repo.OrderRepo','com.example.repo','Repo.java',NULL,NULL,'MAIN',NULL,NULL)");

            // Methods
            s.execute("INSERT INTO nodes VALUES "
                    + "('com.example.ctrl.OrderController#create','create','METHOD',"
                    + "'com.example.ctrl.OrderController#create','com.example.ctrl','Ctrl.java',NULL,NULL,'MAIN',NULL,NULL)");
            s.execute("INSERT INTO nodes VALUES "
                    + "('com.example.svc.OrderService#process','process','METHOD',"
                    + "'com.example.svc.OrderService#process','com.example.svc','Svc.java',NULL,NULL,'MAIN',NULL,NULL)");
            s.execute("INSERT INTO nodes VALUES "
                    + "('com.example.svc.OrderService#validate','validate','METHOD',"
                    + "'com.example.svc.OrderService#validate','com.example.svc','Svc.java',NULL,NULL,'MAIN',NULL,NULL)");
            s.execute("INSERT INTO nodes VALUES "
                    + "('com.example.repo.OrderRepo#save','save','METHOD',"
                    + "'com.example.repo.OrderRepo#save','com.example.repo','Repo.java',NULL,NULL,'MAIN',NULL,NULL)");

            // Field
            s.execute("INSERT INTO nodes VALUES "
                    + "('com.example.svc.OrderService#orderRepo','orderRepo','FIELD',"
                    + "'com.example.svc.OrderService#orderRepo','com.example.svc','Svc.java',NULL,NULL,'MAIN',NULL,NULL)");

            // CONTAINS edges (type → method)
            s.execute("INSERT INTO edges (source_id, target_id, relation, is_external) VALUES "
                    + "('com.example.ctrl.OrderController','com.example.ctrl.OrderController#create','CONTAINS',0)");
            s.execute("INSERT INTO edges (source_id, target_id, relation, is_external) VALUES "
                    + "('com.example.svc.OrderService','com.example.svc.OrderService#process','CONTAINS',0)");
            s.execute("INSERT INTO edges (source_id, target_id, relation, is_external) VALUES "
                    + "('com.example.svc.OrderService','com.example.svc.OrderService#validate','CONTAINS',0)");
            s.execute("INSERT INTO edges (source_id, target_id, relation, is_external) VALUES "
                    + "('com.example.repo.OrderRepo','com.example.repo.OrderRepo#save','CONTAINS',0)");

            // READS edge
            s.execute("INSERT INTO edges (source_id, target_id, relation, is_external) VALUES "
                    + "('com.example.svc.OrderService#process','com.example.svc.OrderService#orderRepo','READS',0)");

            // Annotations
            s.execute("INSERT INTO annotations (node_id, annotation_fqn) VALUES "
                    + "('com.example.ctrl.OrderController','org.springframework.web.bind.annotation.RestController')");
            s.execute("INSERT INTO annotations (node_id, annotation_fqn) VALUES "
                    + "('com.example.svc.OrderService','org.springframework.stereotype.Service')");
            s.execute("INSERT INTO annotations (node_id, annotation_fqn) VALUES "
                    + "('com.example.repo.OrderRepo','org.springframework.stereotype.Repository')");
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (conn != null) conn.close();
    }

    private EdgeRow edge(String src, String tgt, String rel, int depth) {
        EdgeRow e = new EdgeRow();
        e.source = src;
        e.target = tgt;
        e.relation = rel;
        e.isExternal = false;
        e.depth = depth;
        return e;
    }

    @Test
    void emptyChain_returnsEmptyBlocks() {
        CallChainSlicer slicer = new CallChainSlicer(conn);
        SliceResult r = slicer.slice(List.of(), CallChainSlicer.Level.PACKAGE);
        assertEquals("package", r.level);
        assertTrue(r.blocks.isEmpty());
    }

    @Test
    void classLevel_groupsByOwningType() {
        List<EdgeRow> chain = List.of(
                edge("com.example.ctrl.OrderController#create", "com.example.svc.OrderService#process", "CALLS", 1),
                edge("com.example.svc.OrderService#process", "com.example.svc.OrderService#validate", "CALLS", 2),
                edge("com.example.svc.OrderService#validate", "com.example.repo.OrderRepo#save", "CALLS", 3));

        CallChainSlicer slicer = new CallChainSlicer(conn);
        SliceResult r = slicer.slice(chain, CallChainSlicer.Level.CLASS);

        assertEquals("class", r.level);
        assertEquals(3, r.blocks.size());

        BlockResult ctrl = r.blocks.get(0);
        assertEquals("OrderController (CONTROLLER)", ctrl.name);
        assertEquals("CONTROLLER", ctrl.role);
        assertTrue(ctrl.methods.contains("com.example.ctrl.OrderController#create"));

        BlockResult svc = r.blocks.get(1);
        assertEquals("OrderService (SERVICE)", svc.name);
        assertEquals("SERVICE", svc.role);
        assertTrue(svc.methods.contains("com.example.svc.OrderService#process"));
        assertTrue(svc.methods.contains("com.example.svc.OrderService#validate"));
        assertEquals(1, svc.internalEdges.size());

        BlockResult repo = r.blocks.get(2);
        assertEquals("OrderRepo (REPOSITORY)", repo.name);
    }

    @Test
    void packageLevel_mergesSamePackageSameRole() {
        List<EdgeRow> chain = List.of(
                edge("com.example.ctrl.OrderController#create", "com.example.svc.OrderService#process", "CALLS", 1),
                edge("com.example.svc.OrderService#process", "com.example.repo.OrderRepo#save", "CALLS", 2));

        CallChainSlicer slicer = new CallChainSlicer(conn);
        SliceResult r = slicer.slice(chain, CallChainSlicer.Level.PACKAGE);

        assertEquals("package", r.level);
        assertEquals(3, r.blocks.size());
        assertEquals("CONTROLLER", r.blocks.get(0).role);
        assertEquals("SERVICE", r.blocks.get(1).role);
        assertEquals("REPOSITORY", r.blocks.get(2).role);
    }

    @Test
    void fieldsRead_populatedFromDB() {
        List<EdgeRow> chain = List.of(
                edge("com.example.svc.OrderService#process", "com.example.repo.OrderRepo#save", "CALLS", 1));

        CallChainSlicer slicer = new CallChainSlicer(conn);
        SliceResult r = slicer.slice(chain, CallChainSlicer.Level.CLASS);

        BlockResult svc = r.blocks.stream()
                .filter(b -> "SERVICE".equals(b.role))
                .findFirst().orElseThrow();
        assertEquals(1, svc.fieldsRead.size());
        assertEquals("READS", svc.fieldsRead.get(0).relation);
    }

    @Test
    void annotations_collectedFromOwningTypes() {
        List<EdgeRow> chain = List.of(
                edge("com.example.ctrl.OrderController#create", "com.example.svc.OrderService#process", "CALLS", 1));

        CallChainSlicer slicer = new CallChainSlicer(conn);
        SliceResult r = slicer.slice(chain, CallChainSlicer.Level.CLASS);

        BlockResult ctrl = r.blocks.stream()
                .filter(b -> "CONTROLLER".equals(b.role))
                .findFirst().orElseThrow();
        assertTrue(ctrl.annotations.stream().anyMatch(a -> a.contains("RestController")));
    }

    @Test
    void crossBlockEdges_classifiedCorrectly() {
        List<EdgeRow> chain = List.of(
                edge("com.example.ctrl.OrderController#create", "com.example.svc.OrderService#process", "CALLS", 1),
                edge("com.example.svc.OrderService#process", "com.example.repo.OrderRepo#save", "CALLS", 2));

        CallChainSlicer slicer = new CallChainSlicer(conn);
        SliceResult r = slicer.slice(chain, CallChainSlicer.Level.CLASS);

        BlockResult ctrl = r.blocks.get(0);
        assertEquals(1, ctrl.outboundEdges.size());
        assertTrue(ctrl.internalEdges.isEmpty());
        assertTrue(ctrl.inboundEdges.isEmpty());

        BlockResult svc = r.blocks.get(1);
        assertEquals(1, svc.inboundEdges.size());
        assertEquals(1, svc.outboundEdges.size());
    }

    @Test
    void depthRange_trackedPerBlock() {
        List<EdgeRow> chain = List.of(
                edge("com.example.svc.OrderService#process", "com.example.svc.OrderService#validate", "CALLS", 2),
                edge("com.example.svc.OrderService#validate", "com.example.repo.OrderRepo#save", "CALLS", 3));

        CallChainSlicer slicer = new CallChainSlicer(conn);
        SliceResult r = slicer.slice(chain, CallChainSlicer.Level.CLASS);

        BlockResult svc = r.blocks.stream()
                .filter(b -> "SERVICE".equals(b.role))
                .findFirst().orElseThrow();
        assertArrayEquals(new int[]{2, 2}, svc.depthRange);
    }

    @Test
    void archRoles_takePriorityOverAnnotationInference() throws Exception {
        // Insert arch_roles: override SERVICE → APPLICATION, REPOSITORY stays
        try (Statement s = conn.createStatement()) {
            s.execute("INSERT INTO arch_roles VALUES ('com.example.ctrl.OrderController','ENTRY','auto_annotation','@RestController')");
            s.execute("INSERT INTO arch_roles VALUES ('com.example.svc.OrderService','APPLICATION','auto_call_pattern','call pattern')");
            s.execute("INSERT INTO arch_roles VALUES ('com.example.repo.OrderRepo','REPOSITORY','auto_annotation','@Repository')");
        }

        List<EdgeRow> chain = List.of(
                edge("com.example.ctrl.OrderController#create", "com.example.svc.OrderService#process", "CALLS", 1),
                edge("com.example.svc.OrderService#process", "com.example.repo.OrderRepo#save", "CALLS", 2));

        CallChainSlicer slicer = new CallChainSlicer(conn);
        SliceResult r = slicer.slice(chain, CallChainSlicer.Level.CLASS);

        // Should use arch_roles values, not annotation-based inference
        assertEquals("ENTRY", r.blocks.get(0).role);
        assertEquals("APPLICATION", r.blocks.get(1).role);
        assertEquals("REPOSITORY", r.blocks.get(2).role);

        // Cleanup for other tests
        try (Statement s = conn.createStatement()) {
            s.execute("DELETE FROM arch_roles");
        }
    }

    @Test
    void roleInference_coversAllSpringStereotypes() {
        assertEquals("CONTROLLER", CallChainSlicer.inferRole(
                List.of("org.springframework.web.bind.annotation.RestController")));
        assertEquals("CONTROLLER", CallChainSlicer.inferRole(
                List.of("org.springframework.stereotype.Controller")));
        assertEquals("SERVICE", CallChainSlicer.inferRole(
                List.of("org.springframework.stereotype.Service")));
        assertEquals("REPOSITORY", CallChainSlicer.inferRole(
                List.of("org.springframework.stereotype.Repository")));
        assertEquals("COMPONENT", CallChainSlicer.inferRole(
                List.of("org.springframework.stereotype.Component")));
        assertEquals("UNKNOWN", CallChainSlicer.inferRole(List.of()));
        assertEquals("UNKNOWN", CallChainSlicer.inferRole(
                List.of("javax.persistence.Entity")));
    }

    @Test
    void controlFlowContext_collected() {
        EdgeRow e = edge("com.example.svc.OrderService#process", "com.example.repo.OrderRepo#save", "CALLS", 1);
        e.context = "if-then@42";

        CallChainSlicer slicer = new CallChainSlicer(conn);
        SliceResult r = slicer.slice(List.of(e), CallChainSlicer.Level.CLASS);

        BlockResult svc = r.blocks.stream()
                .filter(b -> "SERVICE".equals(b.role))
                .findFirst().orElseThrow();
        assertTrue(svc.controlFlowContext.contains("if-then@42"));
    }
}
