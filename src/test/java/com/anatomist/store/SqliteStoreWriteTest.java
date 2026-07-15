package com.anatomist.store;

import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.model.Node;
import com.anatomist.model.SemanticAnnotation;
import com.anatomist.model.Document;
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
    void schemaIndexesEdgesBySourceFileForIncrementalReplacement(@TempDir Path tmp) throws Exception {
        store = new SqliteStore(tmp.resolve("index.db"));
        store.initSchema();

        assertEquals(1, count(store.connection(),
                "SELECT count(*) FROM sqlite_master WHERE type='index' AND name='idx_edges_source_file'"));
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

    @Test
    void writeSemanticAnnotations_persistsRows(@TempDir Path tmp) throws Exception {
        store = new SqliteStore(tmp.resolve("index.db"));
        store.initSchema();

        ExtractionResult r = new ExtractionResult();
        r.nodes.add(node("com.x.OrderService", "OrderService", "CLASS"));
        SemanticAnnotation sa = new SemanticAnnotation();
        sa.nodeId = "com.x.OrderService";
        sa.category = "REVIEWED";
        sa.source = "CONVENTION";
        sa.confidence = "MEDIUM";
        r.semanticAnnotations.add(sa);

        store.write(r);

        Connection c = store.connection();
        assertEquals(1, count(c,
                "SELECT count(*) FROM semantic_annotations WHERE node_id='com.x.OrderService' AND source='CONVENTION' AND category='REVIEWED'"));
    }

    @Test
    void testFileCacheCrud(@TempDir Path tmp) throws Exception {
        store = new SqliteStore(tmp.resolve("index.db"));
        store.initSchema();

        FileCacheEntry e = new FileCacheEntry("src/A.java", "abc123", 1, "2026-05-31T00:00:00Z", 5, 7);
        store.updateFileCache(java.util.List.of(e));

        java.util.Map<String, FileCacheEntry> cache = store.readFileCache();
        assertEquals(1, cache.size());
        FileCacheEntry read = cache.get("src/A.java");
        assertNotNull(read);
        assertEquals("abc123", read.hash());
        assertEquals(1, read.schemaVersion());
        assertEquals(5, read.nodeCount());
        assertEquals(7, read.edgeCount());
    }

    @Test
    void testProjectMetaCrud(@TempDir Path tmp) throws Exception {
        store = new SqliteStore(tmp.resolve("index.db"));
        store.initSchema();

        store.upsertProjectMeta("java_version", "21");
        store.upsertProjectMeta("classpath_hash", "deadbeef");

        assertEquals("21", store.readProjectMeta("java_version").orElse(null));
        assertEquals("deadbeef", store.readProjectMeta("classpath_hash").orElse(null));

        store.upsertProjectMeta("java_version", "25");
        assertEquals("25", store.readProjectMeta("java_version").orElse(null));
        assertFalse(store.readProjectMeta("nonexistent").isPresent());
    }

    @Test
    void projectMetaBatchIsAtomic(@TempDir Path tmp) throws Exception {
        store = new SqliteStore(tmp.resolve("index.db"));
        store.initSchema();
        try (Statement st = store.connection().createStatement()) {
            st.execute("CREATE TRIGGER reject_bad_meta BEFORE INSERT ON project_meta "
                    + "WHEN NEW.key='bad' BEGIN SELECT RAISE(ABORT, 'bad metadata'); END");
        }

        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("good", "value");
        values.put("bad", "value");
        assertThrows(RuntimeException.class, () -> store.upsertProjectMeta(values));

        assertTrue(store.readProjectMeta("good").isEmpty(),
                "a failed metadata batch must roll back earlier rows");
    }

    @Test
    void testFileDependenciesDerivation(@TempDir Path tmp) throws Exception {
        store = new SqliteStore(tmp.resolve("index.db"));
        store.initSchema();

        ExtractionResult r = new ExtractionResult();
        Node a = node("com.x.A", "A", "CLASS"); a.sourceFile = "A.java";
        Node b = node("com.x.B", "B", "CLASS"); b.sourceFile = "B.java";
        r.nodes.add(a);
        r.nodes.add(b);
        Edge ed = new Edge();
        ed.sourceId = "com.x.A"; ed.targetId = "com.x.B"; ed.relation = "REFERENCES";
        ed.isExternal = false; ed.confidence = "EXTRACTED"; ed.sourceFile = "A.java";
        r.edges.add(ed);
        store.write(r);

        store.deriveFileDependencies();

        Connection c = store.connection();
        assertEquals(1, count(c, "SELECT count(*) FROM file_dependencies WHERE source_file='A.java' AND depends_on_file='B.java'"));
    }

    @Test
    void testDeleteBySourceFiles(@TempDir Path tmp) throws Exception {
        store = new SqliteStore(tmp.resolve("index.db"));
        store.initSchema();

        ExtractionResult r = new ExtractionResult();
        Node a = node("com.x.A", "A", "CLASS"); a.sourceFile = "A.java";
        Node am = node("com.x.A#foo()", "foo", "METHOD"); am.sourceFile = "A.java";
        Node b = node("com.x.B", "B", "CLASS"); b.sourceFile = "B.java";
        r.nodes.add(a); r.nodes.add(am); r.nodes.add(b);
        r.edges.add(containsEdge("com.x.A", "com.x.A#foo()"));
        SemanticAnnotation sa = new SemanticAnnotation();
        sa.nodeId = "com.x.A"; sa.source = "CONVENTION"; sa.confidence = "MEDIUM";
        r.semanticAnnotations.add(sa);
        store.write(r);

        store.deleteBySourceFiles(java.util.List.of("A.java"));

        Connection c = store.connection();
        assertEquals(1, count(c, "SELECT count(*) FROM nodes"));
        assertEquals(0, count(c, "SELECT count(*) FROM nodes WHERE source_file='A.java'"));
        assertEquals(0, count(c, "SELECT count(*) FROM edges"));
        assertEquals(0, count(c, "SELECT count(*) FROM semantic_annotations"));
    }

    @Test
    void sourceGraphReplacementPreservesIncomingEdgesOnlyForStableNodes(@TempDir Path tmp)
            throws Exception {
        store = new SqliteStore(tmp.resolve("index.db"));
        store.initSchema();

        ExtractionResult initial = new ExtractionResult();
        Node a = node("com.x.A", "A", "CLASS"); a.sourceFile = "A.java";
        Node b = node("com.x.B", "B", "CLASS"); b.sourceFile = "B.java";
        initial.nodes.add(a); initial.nodes.add(b);
        Edge incoming = containsEdge("com.x.A", "com.x.B");
        incoming.sourceFile = "A.java";
        initial.edges.add(incoming);
        store.write(initial);

        ExtractionResult stableRewrite = new ExtractionResult();
        Node updatedB = node("com.x.B", "B2", "CLASS"); updatedB.sourceFile = "B.java";
        stableRewrite.nodes.add(updatedB);
        store.inTransaction(c -> store.replaceSourceGraphInCurrentTransaction(
                java.util.List.of("B.java"), stableRewrite));

        Connection c = store.connection();
        assertEquals(1, count(c, "SELECT count(*) FROM edges WHERE source_id='com.x.A' AND target_id='com.x.B'"));
        assertEquals(1, count(c, "SELECT count(*) FROM nodes WHERE id='com.x.B' AND label='B2'"));

        store.inTransaction(ignored -> store.replaceSourceGraphInCurrentTransaction(
                java.util.List.of("B.java"), new ExtractionResult()));
        assertEquals(0, count(c, "SELECT count(*) FROM nodes WHERE id='com.x.B'"));
        assertEquals(0, count(c, "SELECT count(*) FROM edges WHERE target_id='com.x.B'"));
    }

    @Test
    void testDependentsOf(@TempDir Path tmp) throws Exception {
        store = new SqliteStore(tmp.resolve("index.db"));
        store.initSchema();

        // file_dependencies: A.java depends on B.java
        Connection c = store.connection();
        try (Statement st = c.createStatement()) {
            st.execute("INSERT INTO file_dependencies(source_file, depends_on_file) VALUES ('A.java', 'B.java')");
        }

        java.util.Set<String> deps = store.dependentsOf(java.util.List.of("B.java"));
        assertEquals(1, deps.size());
        assertTrue(deps.contains("A.java"));
        assertTrue(store.dependentsOf(java.util.List.of("A.java")).isEmpty());
    }

    @Test
    void incrementalDependencyRefreshPreservesUnrelatedRows(@TempDir Path tmp) throws Exception {
        store = new SqliteStore(tmp.resolve("index.db"));
        store.initSchema();
        ExtractionResult result = new ExtractionResult();
        Node a = node("A", "A", "CLASS"); a.sourceFile = "A.java";
        Node b = node("B", "B", "CLASS"); b.sourceFile = "B.java";
        Node c = node("C", "C", "CLASS"); c.sourceFile = "C.java";
        Node d = node("D", "D", "CLASS"); d.sourceFile = "D.java";
        result.nodes.addAll(java.util.List.of(a, b, c, d));
        Edge ab = containsEdge("A", "B"); ab.sourceFile = "A.java";
        Edge cd = containsEdge("C", "D"); cd.sourceFile = "C.java";
        result.edges.addAll(java.util.List.of(ab, cd));
        store.write(result);
        store.deriveFileDependencies();

        try (Statement st = store.connection().createStatement()) {
            st.execute("DELETE FROM edges WHERE source_id='A' AND target_id='B'");
        }
        store.refreshFileDependencies(java.util.List.of("A.java", "B.java"));

        Connection connection = store.connection();
        assertEquals(0, count(connection,
                "SELECT count(*) FROM file_dependencies WHERE source_file='A.java'"));
        assertEquals(1, count(connection,
                "SELECT count(*) FROM file_dependencies WHERE source_file='C.java' AND depends_on_file='D.java'"));
    }

    @Test
    void insertDocuments_persistsRowsAndSyncsFts(@TempDir Path tmp) throws Exception {
        store = new SqliteStore(tmp.resolve("index.db"));
        store.initSchema();

        Document d = new Document();
        d.path = "README.md";
        d.title = "Mini Spring Shop";
        d.content = "A demo Spring Boot project for testing anatomist.";
        d.docType = "README";
        d.module = null;
        store.insertDocuments(java.util.List.of(d));

        Connection c = store.connection();
        assertEquals(1, count(c, "SELECT count(*) FROM documents WHERE path='README.md'"));
        assertEquals(1, count(c, "SELECT count(*) FROM doc_content WHERE doc_content MATCH 'anatomist'"));
    }
}
