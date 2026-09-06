package com.anatomist.store;

import com.anatomist.core.NodeKeyFactory;
import com.anatomist.core.SourceIdentity;
import com.anatomist.core.SourceIdentityResolver;
import com.anatomist.core.SourceRoot;
import com.anatomist.core.SourceScope;
import com.anatomist.model.Annotation;
import com.anatomist.model.Declaration;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StagedGraphStoreTest {

    @Test
    void incrementalPublicationRollsBackDerivedStateAndChecksRevision(@TempDir Path tmp)
            throws Exception {
        Path db = tmp.resolve("index.db");
        String file = "m1/src/main/java/A.java";
        try (SqliteStore target = new SqliteStore(db)) {
            try (StagedGraphStore initial = new StagedGraphStore(db, identities(tmp))) {
                ExtractionResult facts = new ExtractionResult();
                facts.nodes.add(node("com.x.A", file));
                initial.writeRawBatch(facts);
                initial.promoteFull(target);
            }
            String revision = target.readProjectMeta(IndexRevision.META_KEY).orElseThrow();
            try (StagedGraphStore update = new StagedGraphStore(db, identities(tmp))) {
                ExtractionResult facts = new ExtractionResult();
                Node changed = node("com.x.A", file);
                changed.label = "changed";
                facts.nodes.add(changed);
                update.writeRawBatch(facts);
                assertThrows(RuntimeException.class, () -> update.promoteIncremental(
                        target, List.of(file), Set.of(), false, revision, () -> {
                            target.upsertProjectMeta("publication_probe", "should-rollback");
                            throw new SQLException("injected publication failure");
                        }));
            }
            try (Statement statement = target.connection().createStatement()) {
                assertEquals(1, scalar(statement,
                        "SELECT count(*) FROM nodes WHERE symbol_id='com.x.A' AND label='A'"));
                assertEquals(0, scalar(statement,
                        "SELECT count(*) FROM project_meta WHERE key='publication_probe'"));
                assertEquals(revision, target.readProjectMeta(IndexRevision.META_KEY).orElseThrow());
            }

            target.inTransaction(IndexRevision::bump);
            try (StagedGraphStore stale = new StagedGraphStore(db, identities(tmp))) {
                ExtractionResult facts = new ExtractionResult();
                facts.nodes.add(node("com.x.A", file));
                stale.writeRawBatch(facts);
                RuntimeException conflict = assertThrows(RuntimeException.class,
                        () -> stale.promoteIncremental(
                                target, List.of(file), Set.of(), false, revision));
                assertTrue(rootMessage(conflict).contains("revision changed"));
            }
        }
    }

    @Test
    void unchangedFactsAreNotRewrittenAndDuplicateMultiplicityIsPreserved(
            @TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("index.db");
        String file = "m1/src/main/java/A.java";
        try (SqliteStore target = new SqliteStore(db)) {
            try (StagedGraphStore initial = new StagedGraphStore(db, identities(tmp))) {
                ExtractionResult facts = graphWithDuplicateEdges(file, 2);
                initial.writeRawBatch(facts);
                initial.finalizeRawFacts();
                initial.promoteFull(target);
            }
            try (Statement statement = target.connection().createStatement()) {
                statement.execute("CREATE TABLE fact_audit(kind TEXT)");
                statement.execute("CREATE TRIGGER audit_node_update AFTER UPDATE ON nodes "
                        + "BEGIN INSERT INTO fact_audit VALUES('node-update'); END");
                statement.execute("CREATE TRIGGER audit_edge_delete AFTER DELETE ON edges "
                        + "BEGIN INSERT INTO fact_audit VALUES('edge-delete'); END");
            }

            StagedGraphStore.IncrementalPromotionStats unchanged;
            try (StagedGraphStore update = new StagedGraphStore(db, identities(tmp))) {
                update.writeRawBatch(graphWithDuplicateEdges(file, 2));
                update.finalizeRawFacts();
                unchanged = update.promoteIncremental(target, List.of(file), false, false);
            }
            try (Statement statement = target.connection().createStatement()) {
                assertEquals(0, scalar(statement, "SELECT count(*) FROM fact_audit"));
                assertEquals(2, scalar(statement, "SELECT count(*) FROM edges"));
                assertEquals(2, unchanged.unchangedEdges());
                assertEquals(0, unchanged.insertedEdges());
                statement.executeUpdate("DELETE FROM fact_audit");
            }

            try (StagedGraphStore update = new StagedGraphStore(db, identities(tmp))) {
                update.writeRawBatch(graphWithDuplicateEdges(file, 1));
                update.finalizeRawFacts();
                update.promoteIncremental(target, List.of(file), false, false);
            }
            try (Statement statement = target.connection().createStatement()) {
                assertEquals(1, scalar(statement, "SELECT count(*) FROM edges"));
                assertEquals(1, scalar(statement,
                        "SELECT count(*) FROM fact_audit WHERE kind='edge-delete'"));
            }
        }
    }

    @Test
    void fullAndIncrementalPromotionEmbedAndClearDeclarationFacet(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("index.db");
        String file = "m1/src/main/java/A.java";
        try (SqliteStore target = new SqliteStore(db)) {
            try (StagedGraphStore initial = new StagedGraphStore(db, identities(tmp))) {
                ExtractionResult facts = new ExtractionResult();
                facts.nodes.add(node("com.x.A", file));
                facts.declarations.add(declaration("com.x.A", file, "public"));
                initial.writeRawBatch(facts);
                initial.promoteFull(target);
            }
            try (Statement statement = target.connection().createStatement()) {
                assertEquals(1, scalar(statement, "SELECT count(*) FROM nodes WHERE symbol_id='com.x.A' "
                        + "AND declaration_kind='type' AND visibility='public'"));
            }

            try (StagedGraphStore update = new StagedGraphStore(db, identities(tmp))) {
                ExtractionResult facts = new ExtractionResult();
                facts.nodes.add(node("com.x.A", file));
                facts.declarations.add(declaration("com.x.A", file, "private"));
                update.writeRawBatch(facts);
                update.promoteIncremental(target, List.of(file), false, false);
            }
            try (Statement statement = target.connection().createStatement()) {
                assertEquals(1, scalar(statement, "SELECT count(*) FROM nodes WHERE symbol_id='com.x.A' "
                        + "AND visibility='private'"));
            }

            try (StagedGraphStore update = new StagedGraphStore(db, identities(tmp))) {
                ExtractionResult facts = new ExtractionResult();
                facts.nodes.add(node("com.x.A", file));
                update.writeRawBatch(facts);
                update.promoteIncremental(target, List.of(file), false, false);
            }
            try (Statement statement = target.connection().createStatement()) {
                assertEquals(1, scalar(statement, "SELECT count(*) FROM nodes WHERE symbol_id='com.x.A' "
                        + "AND declaration_kind IS NULL AND visibility IS NULL"));
            }
        }
    }

    @Test
    void unmatchedStagedDeclarationRejectsPromotion(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("index.db");
        try (StagedGraphStore staging = new StagedGraphStore(db, identities(tmp));
             SqliteStore target = new SqliteStore(db)) {
            ExtractionResult facts = new ExtractionResult();
            facts.nodes.add(node("com.x.A", "m1/src/main/java/A.java"));
            facts.declarations.add(declaration("com.x.Missing", "m1/src/main/java/A.java", "public"));
            staging.writeRawBatch(facts);

            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> staging.promoteFull(target));
            assertTrue(rootMessage(failure).contains("DECLARATION_NODE_MISSING"));
            try (Statement statement = target.connection().createStatement()) {
                assertEquals(0, scalar(statement, "SELECT count(*) FROM nodes"));
            }
        }
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
    void rejectsCrossProducerNodeOwnershipOnSharedResource(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("index.db");
        try (StagedGraphStore staging = new StagedGraphStore(db, identities(tmp))) {
            ExtractionResult first = new ExtractionResult();
            Node one = node("p.Shared", "m1/src/main/java/Shared.java");
            one.producerId = "producer-a";
            first.nodes.add(one);
            staging.writeRawBatch(first);

            ExtractionResult second = new ExtractionResult();
            Node two = node("p.Shared", "m1/src/main/java/Shared.java");
            two.producerId = "producer-b";
            second.nodes.add(two);
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> staging.writeRawBatch(second));
            assertTrue(failure.getMessage().contains("staging batch"));
            assertTrue(rootMessage(failure).contains("EXTENSION_NODE_OWNERSHIP_CONFLICT"));
        }
    }

    @Test
    void projectProducerReplacementPreservesStableNodesAndOtherProducerFacts(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("index.db");
        try (SqliteStore target = new SqliteStore(db)) {
            try (StagedGraphStore initial = new StagedGraphStore(db, identities(tmp))) {
                ExtractionResult facts = new ExtractionResult();
                Node stable = node("project:stable", "shared.xml");
                stable.producerId = "producer-a";
                Node obsolete = node("project:obsolete", "shared.xml");
                obsolete.producerId = "producer-a";
                Node peer = node("project:peer", "shared.xml");
                peer.producerId = "producer-b";
                facts.nodes.addAll(List.of(stable, obsolete, peer));
                Edge shared = edge(peer.id, stable.id, "shared.xml");
                shared.producerId = "producer-b";
                facts.edges.add(shared);
                initial.writeNormalizedBatch(facts);
                initial.promoteFull(target);
            }

            try (StagedGraphStore update = new StagedGraphStore(db, identities(tmp))) {
                ExtractionResult facts = new ExtractionResult();
                Node stable = node("project:stable", "shared.xml");
                stable.label = "updated";
                stable.producerId = "producer-a";
                facts.nodes.add(stable);
                update.writeNormalizedBatch(facts);
                update.promoteIncremental(target, List.of(), Set.of("producer-a"), false);
            }

            try (Statement statement = target.connection().createStatement()) {
                assertEquals(1, scalar(statement,
                        "SELECT count(*) FROM nodes WHERE id='project:stable' AND label='updated'"));
                assertEquals(0, scalar(statement,
                        "SELECT count(*) FROM nodes WHERE id='project:obsolete'"));
                assertEquals(1, scalar(statement,
                        "SELECT count(*) FROM nodes WHERE id='project:peer' AND producer_id='producer-b'"));
                assertEquals(1, scalar(statement,
                        "SELECT count(*) FROM edges WHERE source_id='project:peer' "
                                + "AND target_id='project:stable' AND producer_id='producer-b'"));
            }
        }
    }

    @Test
    void rawBeanTargetsUsesLogicalSourceIdentityBeforeFinalization(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("index.db");
        try (StagedGraphStore staging = new StagedGraphStore(db, identities(tmp))) {
            ExtractionResult facts = new ExtractionResult();
            Node bean = node("bean:shared", "m1/src/main/java/Shared.java");
            bean.kind = GraphConstants.Kind.BEAN;
            facts.nodes.add(bean);
            Edge definition = edge(bean.id, "example.Shared", bean.sourceFile);
            definition.relation = GraphConstants.Relation.DEFINED_BY;
            facts.edges.add(definition);
            staging.writeRawBatch(facts);

            assertEquals("example.Shared", staging.rawBeanTargets().get("bean:shared").className());
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable cursor = failure;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return String.valueOf(cursor.getMessage());
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

    private static Declaration declaration(String symbol, String sourceFile, String visibility) {
        Declaration declaration = new Declaration();
        declaration.symbolId = symbol;
        declaration.qualifiedName = symbol;
        declaration.label = symbol.substring(symbol.lastIndexOf('.') + 1);
        declaration.kind = GraphConstants.Kind.CLASS;
        declaration.declarationKind = "type";
        declaration.typeKind = "class";
        declaration.visibility = visibility;
        declaration.sourceFile = sourceFile;
        declaration.directMember = true;
        declaration.bindingResolved = true;
        return declaration;
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

    private static ExtractionResult graphWithDuplicateEdges(String sourceFile, int duplicates) {
        ExtractionResult facts = new ExtractionResult();
        facts.nodes.add(node("com.x.A", sourceFile));
        facts.nodes.add(node("com.x.B", "m1/src/main/java/B.java"));
        for (int i = 0; i < duplicates; i++) {
            facts.edges.add(edge("com.x.A", "com.x.B", sourceFile));
        }
        return facts;
    }

    private static int scalar(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }
}
