package com.anatomist.store;

import com.anatomist.core.NodeKeyFactory;
import com.anatomist.core.IndexTimings;
import com.anatomist.core.SourceIdentity;
import com.anatomist.core.SourceIdentityResolver;
import com.anatomist.core.SourceRoot;
import com.anatomist.core.SourceScope;
import com.anatomist.flow.FlowEdge;
import com.anatomist.flow.FlowNode;
import com.anatomist.flow.FlowPersistence;
import com.anatomist.flow.FlowResult;
import com.anatomist.model.Annotation;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;
import com.anatomist.incremental.IncrementalSessionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StagedGraphStoreTest {

    @Test
    void watchSessionReusesEmptyStagingSchemaAndDeletesItOnClose(@TempDir Path tmp) throws Exception {
        Path index = tmp.resolve("index.db");
        Path reusable;
        try (IncrementalSessionState session = new IncrementalSessionState()) {
            reusable = session.stagingPath(index);
            try (StagedGraphStore first = new StagedGraphStore(index, identities(tmp), reusable)) {
                ExtractionResult result = new ExtractionResult();
                result.nodes.add(node("p.A", "m1/src/main/java/A.java"));
                first.writeRawBatch(result);
            }
            assertTrue(Files.exists(reusable));

            try (StagedGraphStore second = new StagedGraphStore(index, identities(tmp), reusable);
                 Connection check = DriverManager.getConnection("jdbc:sqlite:" + reusable);
                 Statement statement = check.createStatement()) {
                assertEquals(0, scalar(statement, "SELECT count(*) FROM stage_nodes"));
            }
        }
        assertFalse(Files.exists(reusable));
    }

    @Test
    void rawFactsResolveAcrossFilesAndAmbiguityBecomesExternal(@TempDir Path tmp) throws Exception {
        SourceIdentityResolver identities = identities(tmp);
        Path db = tmp.resolve("index.db");
        try (StagedGraphStore staging = new StagedGraphStore(db, identities);
             SqliteStore target = new SqliteStore(db)) {
            ExtractionResult result = new ExtractionResult();
            result.nodes.add(node("com.x.A", "m1/src/main/java/A.java"));
            result.nodes.add(node("com.x.B", "m1/src/main/java/B.java"));
            result.nodes.add(node("com.x.Shared", "m1/src/main/java/S1.java"));
            result.nodes.add(node("com.x.Shared", "m2/src/main/java/S2.java"));
            result.nodes.add(node("com.x.C", "m3/src/main/java/C.java"));

            result.edges.add(edge("com.x.A", "com.x.B", "m1/src/main/java/A.java"));
            result.edges.add(edge("com.x.C", "com.x.Shared", "m3/src/main/java/C.java"));
            Annotation annotation = new Annotation();
            annotation.nodeId = "com.x.A";
            annotation.annotationFqn = "com.x.Tag";
            annotation.sourceFile = "m1/src/main/java/A.java";
            result.annotations.add(annotation);

            staging.writeRawBatch(result);
            staging.finalizeRawFacts();
            staging.promoteFull(target);

            String a = NodeKeyFactory.key(new SourceIdentity("m1", SourceScope.MAIN), "com.x.A");
            String b = NodeKeyFactory.key(new SourceIdentity("m1", SourceScope.MAIN), "com.x.B");
            try (Statement statement = target.connection().createStatement()) {
                assertEquals(1, scalar(statement,
                        "SELECT count(*) FROM edges WHERE source_id='" + a + "' AND target_id='" + b + "'"));
                assertEquals(1, scalar(statement,
                        "SELECT count(*) FROM annotations WHERE node_id='" + a + "'"));
                assertEquals(1, scalar(statement,
                        "SELECT count(*) FROM edges WHERE is_external=1 "
                                + "AND external_target_fqn='com.x.Shared' AND confidence='AMBIGUOUS'"));
            }
        }
    }

    @Test
    void failedFullPromotionRollsBackOldGraphAndCleansSidecar(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("index.db");
        Path stagingPath;
        try (SqliteStore target = new SqliteStore(db)) {
            target.initSchema();
            ExtractionResult old = new ExtractionResult();
            old.nodes.add(node("old.Node", "m1/src/main/java/Old.java"));
            old.nodes.get(0).id = "old.Node";
            old.nodes.get(0).symbolId = "old.Node";
            old.nodes.get(0).module = ".";
            old.nodes.get(0).scope = "MAIN";
            target.write(old);

            try (StagedGraphStore staging = new StagedGraphStore(db, identities(tmp))) {
                stagingPath = staging.path();
                ExtractionResult next = new ExtractionResult();
                next.nodes.add(node("new.Node", "m1/src/main/java/New.java"));
                staging.writeRawBatch(next);
                staging.finalizeRawFacts();
                try (Statement statement = target.connection().createStatement()) {
                    statement.execute("CREATE TRIGGER reject_new BEFORE INSERT ON nodes "
                            + "WHEN NEW.symbol_id='new.Node' BEGIN SELECT RAISE(ABORT,'reject'); END");
                }
                assertThrows(RuntimeException.class, () -> staging.promoteFull(target));
                try (Statement statement = target.connection().createStatement()) {
                    assertEquals(1, scalar(statement,
                            "SELECT count(*) FROM nodes WHERE id='old.Node'"));
                }
            }
        }
        assertFalse(Files.exists(stagingPath));
    }

    @Test
    void failedFlowPromotionRestoresOldFactsAndDroppedIndexes(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("index.db");
        try (SqliteStore target = new SqliteStore(db)) {
            target.initSchema();
            FlowResult old = new FlowResult();
            old.nodes.add(flowNode("old-node", "old-method", "Old.java"));
            FlowPersistence.replaceAll(target, old, null);

            try (StagedGraphStore staging = new StagedGraphStore(db, identities(tmp))) {
                FlowResult invalid = new FlowResult();
                invalid.nodes.add(flowNode("new-node", "new-method", "New.java"));
                invalid.edges.add(new FlowEdge(
                        "new-node", "missing-node", "DEF_USE", "new-method",
                        "New.java", "EXACT", null, null));
                staging.writeFlowBatch(invalid);

                assertThrows(RuntimeException.class,
                        () -> staging.promoteFullFlow(target, new IndexTimings()));
            }

            try (Statement statement = target.connection().createStatement()) {
                assertEquals(1, scalar(statement,
                        "SELECT count(*) FROM flow_nodes WHERE id='old-node'"));
                assertEquals(1, scalar(statement,
                        "SELECT count(*) FROM pragma_index_list('flow_edges') "
                                + "WHERE name='idx_flow_edges_source'"));
                assertEquals(0, scalar(statement,
                        "SELECT count(*) FROM pragma_foreign_key_check"));
            }
        }
    }

    private static SourceIdentityResolver identities(Path root) throws Exception {
        Path m1 = Files.createDirectories(root.resolve("m1/src/main/java"));
        Path m2 = Files.createDirectories(root.resolve("m2/src/main/java"));
        Path m3 = Files.createDirectories(root.resolve("m3/src/main/java"));
        return SourceIdentityResolver.fromRoots(root, List.of(
                new SourceRoot(m1, "m1", SourceScope.MAIN),
                new SourceRoot(m2, "m2", SourceScope.MAIN),
                new SourceRoot(m3, "m3", SourceScope.MAIN)));
    }

    private static Node node(String id, String sourceFile) {
        Node node = new Node();
        node.id = id;
        node.label = id.substring(id.lastIndexOf('.') + 1);
        node.kind = GraphConstants.Kind.CLASS;
        node.qualifiedName = id;
        node.sourceFile = sourceFile;
        return node;
    }

    private static Edge edge(String source, String target, String sourceFile) {
        Edge edge = new Edge();
        edge.sourceId = source;
        edge.targetId = target;
        edge.relation = GraphConstants.Relation.REFERENCES;
        edge.confidence = GraphConstants.Confidence.EXTRACTED;
        edge.sourceFile = sourceFile;
        return edge;
    }

    private static FlowNode flowNode(String id, String methodId, String sourceFile) {
        return new FlowNode(
                id, methodId, "EXPRESSION", id, sourceFile,
                ".", "MAIN", 1, 1, null, null, null);
    }

    private static int scalar(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }
}
