package com.anatomist.store;

import com.anatomist.model.Annotation;
import com.anatomist.model.Document;
import com.anatomist.model.Edge;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;
import com.anatomist.model.SemanticAnnotation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

public class DataWriter {
    private static final String SQL_DELETE_SEMANTIC_ANNOTATION_BY_KEY =
            "DELETE FROM semantic_annotations WHERE node_id=? AND category=? AND source=?";
    private static final String SQL_INSERT_SEMANTIC_ANNOTATION =
            "INSERT INTO semantic_annotations"
                    + "(node_id,doc_id,category,business_label,business_description,domain_context,source,confidence)"
                    + " VALUES (?,?,?,?,?,?,?,?)";
    private static final String SQL_INSERT_DOCUMENT =
            "INSERT INTO documents(path,title,content,doc_type,module) VALUES (?,?,?,?,?)";
    private static final String SQL_INSERT_FILE_CACHE =
            "INSERT OR REPLACE INTO file_cache"
                    + "(source_file,hash,schema_version,last_indexed,node_count,edge_count)"
                    + " VALUES (?,?,?,?,?,?)";
    private static final String SQL_UPSERT_PROJECT_META =
            "INSERT INTO project_meta(key,value) VALUES (?,?) "
                    + "ON CONFLICT(key) DO UPDATE SET value=excluded.value";
    private static final String SQL_DELETE_SEMANTIC_ANNOTATIONS_BY_SOURCE_FILE =
            "DELETE FROM semantic_annotations WHERE node_id IN (SELECT id FROM nodes WHERE source_file=?)";
    private static final String SQL_DELETE_FILE_CACHE_BY_SOURCE_FILE =
            "DELETE FROM file_cache WHERE source_file=?";
    private static final String SQL_DELETE_NODES_BY_SOURCE_FILE =
            "DELETE FROM nodes WHERE source_file=?";
    private static final String SQL_DELETE_WIRING_EDGES =
            "DELETE FROM edges WHERE relation='" + GraphConstants.Relation.WIRES + "'";
    private static final String SQL_DELETE_GENERATED_WIRING_EDGES =
            "DELETE FROM edges WHERE metadata LIKE '%\"via\":\"" + GraphConstants.MetadataVia.INJECTION + "\"%'"
                    + " OR metadata LIKE '%\"via\":\"" + GraphConstants.MetadataVia.INJECTED_CALL + "\"%'";
    private static final String SQL_DELETE_XML_BEAN_GRAPH =
            "DELETE FROM nodes WHERE source_file LIKE '%.xml' AND ("
                    + "kind='" + GraphConstants.Kind.BEAN + "'"
                    + " OR kind LIKE 'XML_%')";
    private static final String SQL_DELETE_FILE_DEPENDENCIES =
            "DELETE FROM file_dependencies";
    private static final String SQL_DERIVE_FILE_DEPENDENCIES = """
            INSERT OR IGNORE INTO file_dependencies(source_file, depends_on_file)
            SELECT DISTINCT sn.source_file, tn.source_file
            FROM edges e
            JOIN nodes sn ON e.source_id = sn.id
            JOIN nodes tn ON e.target_id = tn.id
            WHERE e.is_external=0
            AND sn.source_file IS NOT NULL AND tn.source_file IS NOT NULL
            AND sn.source_file <> tn.source_file
            """;
    private static final String SQL_INSERT_NODE =
            "INSERT OR REPLACE INTO nodes"
                    + "(id,label,kind,qualified_name,package,source_file,source_location,module,scope,javadoc,metadata)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?)";
    private static final String SQL_INSERT_EDGE =
            "INSERT INTO edges"
                    + "(source_id,target_id,external_target_fqn,relation,call_kind,confidence,context,is_external,source_file,source_location,metadata)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?)";
    private static final String SQL_INSERT_ANNOTATION =
            "INSERT INTO annotations(node_id,annotation_fqn,attributes) VALUES (?,?,?)";

    private final ConnectionSupplier connSupplier;

    public DataWriter(ConnectionSupplier connSupplier) {
        this.connSupplier = connSupplier;
    }

    @FunctionalInterface
    public interface TxWork {
        void run(Connection c) throws SQLException;
    }

    public void inTransaction(TxWork work) {
        Connection c;
        try {
            c = connSupplier.get();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire SQLite connection", e);
        }
        boolean priorAutoCommit;
        try {
            priorAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to begin transaction", e);
        }
        try {
            work.run(c);
            c.commit();
        } catch (RuntimeException | SQLException e) {
            try { c.rollback(); } catch (SQLException ignore) {}
            throw new RuntimeException("Transaction failed: " + e.getMessage(), e);
        } finally {
            try { c.setAutoCommit(priorAutoCommit); } catch (SQLException ignore) {}
        }
    }

    public void writeNodes(List<Node> nodes) {
        if (nodes == null || nodes.isEmpty()) return;
        inTransaction(c -> insertNodes(c, nodes));
    }

    public void writeInCurrentTransaction(com.anatomist.model.ExtractionResult result) {
        if (result == null) return;
        Connection c;
        try {
            c = connSupplier.get();
            insertNodes(c, result.nodes);
            insertEdges(c, result.edges);
            insertAnnotations(c, result.annotations);
            insertSemanticAnnotations(c, result.semanticAnnotations);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to write graph in current transaction", e);
        }
    }

    public void writeEdgesBatched(List<Edge> edges, int batchSize) {
        if (edges == null || edges.isEmpty()) return;
        for (int off = 0; off < edges.size(); off += batchSize) {
            List<Edge> batch = edges.subList(off, Math.min(off + batchSize, edges.size()));
            inTransaction(c -> insertEdges(c, batch));
        }
    }

    public void writeAnnotationsBatched(List<Annotation> annotations,
                                         List<SemanticAnnotation> semanticAnnotations,
                                         int batchSize) {
        if (annotations != null && !annotations.isEmpty()) {
            for (int off = 0; off < annotations.size(); off += batchSize) {
                List<Annotation> batch = annotations.subList(off, Math.min(off + batchSize, annotations.size()));
                inTransaction(c -> insertAnnotations(c, batch));
            }
        }
        if (semanticAnnotations != null && !semanticAnnotations.isEmpty()) {
            for (int off = 0; off < semanticAnnotations.size(); off += batchSize) {
                List<SemanticAnnotation> batch = semanticAnnotations.subList(off, Math.min(off + batchSize, semanticAnnotations.size()));
                inTransaction(c -> insertSemanticAnnotations(c, batch));
            }
        }
    }

    public void runAnalyze() {
        try (Statement st = connSupplier.get().createStatement()) {
            st.execute("ANALYZE");
        } catch (SQLException e) {
            // non-fatal
        }
    }

    public void upsertSemanticAnnotation(SemanticAnnotation sa) {
        if (sa == null) return;
        upsertSemanticAnnotations(List.of(sa));
    }

    public void upsertSemanticAnnotations(List<SemanticAnnotation> sas) {
        if (sas == null || sas.isEmpty()) return;
        inTransaction(c -> {
            try (PreparedStatement psDel = c.prepareStatement(SQL_DELETE_SEMANTIC_ANNOTATION_BY_KEY);
                 PreparedStatement psIns = c.prepareStatement(SQL_INSERT_SEMANTIC_ANNOTATION)) {
                for (SemanticAnnotation sa : sas) {
                    psDel.setString(1, sa.nodeId);
                    psDel.setString(2, sa.category);
                    psDel.setString(3, sa.source);
                    psDel.executeUpdate();

                    bindSemanticAnnotation(psIns, sa);
                    psIns.executeUpdate();
                }
            }
        });
    }

    public void insertDocuments(List<Document> docs) {
        if (docs == null || docs.isEmpty()) return;
        inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_DOCUMENT)) {
                for (Document d : docs) {
                    bindDocument(ps, d);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    public void replaceDocuments(List<Document> docs) {
        inTransaction(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM documents");
            }
            if (docs == null || docs.isEmpty()) return;
            try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_DOCUMENT)) {
                for (Document d : docs) {
                    bindDocument(ps, d);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    public void updateFileCache(List<FileCacheEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        Connection c;
        try {
            c = connSupplier.get();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire SQLite connection", e);
        }
        try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_FILE_CACHE)) {
            for (FileCacheEntry entry : entries) {
                ps.setString(1, entry.sourceFile());
                ps.setString(2, entry.hash());
                ps.setInt(3, entry.schemaVersion());
                ps.setString(4, entry.lastIndexed() == null ? Instant.now().toString() : entry.lastIndexed());
                ps.setInt(5, entry.nodeCount());
                ps.setInt(6, entry.edgeCount());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update file_cache", e);
        }
    }

    public void upsertProjectMeta(String key, String value) {
        Connection c;
        try {
            c = connSupplier.get();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire SQLite connection", e);
        }
        try (PreparedStatement ps = c.prepareStatement(SQL_UPSERT_PROJECT_META)) {
            ps.setString(1, key);
            setNullableString(ps, 2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert project_meta", e);
        }
    }

    public void deleteBySourceFiles(List<String> sourceFiles) {
        if (sourceFiles == null || sourceFiles.isEmpty()) return;
        Connection c;
        try {
            c = connSupplier.get();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire SQLite connection", e);
        }
        try (PreparedStatement psSem = c.prepareStatement(SQL_DELETE_SEMANTIC_ANNOTATIONS_BY_SOURCE_FILE);
             PreparedStatement psFc = c.prepareStatement(SQL_DELETE_FILE_CACHE_BY_SOURCE_FILE);
             PreparedStatement psNodes = c.prepareStatement(SQL_DELETE_NODES_BY_SOURCE_FILE)) {
            for (String f : sourceFiles) {
                psSem.setString(1, f); psSem.addBatch();
                psNodes.setString(1, f); psNodes.addBatch();
                psFc.setString(1, f); psFc.addBatch();
            }
            psSem.executeBatch();
            psNodes.executeBatch();
            psFc.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete by source files", e);
        }
    }

    public void deleteSpringBeanGraph() {
        Connection c;
        try {
            c = connSupplier.get();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire SQLite connection", e);
        }
        try (Statement st = c.createStatement()) {
            st.execute(SQL_DELETE_WIRING_EDGES);
            st.execute(SQL_DELETE_XML_BEAN_GRAPH);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete spring bean graph", e);
        }
    }

    public void replaceGeneratedWiringEdges(List<Edge> edges) {
        inTransaction(c -> replaceGeneratedWiringEdges(c, edges));
    }

    void replaceGeneratedWiringEdgesInCurrentTransaction(List<Edge> edges) {
        Connection c;
        try {
            c = connSupplier.get();
            replaceGeneratedWiringEdges(c, edges);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to replace generated wiring edges", e);
        }
    }

    public void clearFileDependencies() {
        Connection c;
        try {
            c = connSupplier.get();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire SQLite connection", e);
        }
        try (Statement st = c.createStatement()) {
            st.execute(SQL_DELETE_FILE_DEPENDENCIES);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear file_dependencies", e);
        }
    }

    public void deriveFileDependencies() {
        Connection c;
        try {
            c = connSupplier.get();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire SQLite connection", e);
        }
        try (Statement st = c.createStatement()) {
            st.execute(SQL_DERIVE_FILE_DEPENDENCIES);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to derive file_dependencies", e);
        }
    }

    public void refreshFileDependencies() {
        Connection c;
        try {
            c = connSupplier.get();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire SQLite connection", e);
        }
        try (Statement st = c.createStatement()) {
            st.execute(SQL_DELETE_FILE_DEPENDENCIES);
            st.execute(SQL_DERIVE_FILE_DEPENDENCIES);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to refresh file_dependencies", e);
        }
    }

    // ── Package-private insert methods (used by SqliteStore.write()) ──

    static void insertNodes(Connection c, List<Node> nodes) throws SQLException {
        if (nodes == null || nodes.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_NODE)) {
            for (Node n : nodes) {
                ps.setString(1, n.id);
                ps.setString(2, n.label);
                ps.setString(3, n.kind);
                ps.setString(4, n.qualifiedName);
                ps.setString(5, n.pkg);
                ps.setString(6, n.sourceFile == null ? "" : n.sourceFile);
                ps.setString(7, n.sourceLocation);
                ps.setString(8, n.module);
                ps.setString(9, n.scope == null ? GraphConstants.Scope.MAIN : n.scope);
                ps.setString(10, n.javadoc);
                ps.setString(11, n.metadata);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    static void insertEdges(Connection c, List<Edge> edges) throws SQLException {
        if (edges == null || edges.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_EDGE)) {
            for (Edge e : edges) {
                ps.setString(1, e.sourceId);
                setNullableString(ps, 2, e.targetId);
                setNullableString(ps, 3, e.externalTargetFqn);
                ps.setString(4, e.relation);
                setNullableString(ps, 5, e.callKind);
                ps.setString(6, e.confidence == null ? GraphConstants.Confidence.EXTRACTED : e.confidence);
                setNullableString(ps, 7, e.context);
                ps.setInt(8, e.isExternal ? 1 : 0);
                ps.setString(9, e.sourceFile);
                ps.setString(10, e.sourceLocation);
                ps.setString(11, e.metadata);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    static void replaceGeneratedWiringEdges(Connection c, List<Edge> edges) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(SQL_DELETE_GENERATED_WIRING_EDGES);
        }
        insertEdges(c, edges);
    }

    static void insertAnnotations(Connection c, List<Annotation> anns) throws SQLException {
        if (anns == null || anns.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_ANNOTATION)) {
            for (Annotation a : anns) {
                ps.setString(1, a.nodeId);
                ps.setString(2, a.annotationFqn);
                ps.setString(3, a.attributes);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    static void insertSemanticAnnotations(Connection c, List<SemanticAnnotation> sas) throws SQLException {
        if (sas == null || sas.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_SEMANTIC_ANNOTATION)) {
            for (SemanticAnnotation sa : sas) {
                bindSemanticAnnotation(ps, sa);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void bindDocument(PreparedStatement ps, Document d) throws SQLException {
        ps.setString(1, d.path);
        setNullableString(ps, 2, d.title);
        setNullableString(ps, 3, d.content);
        ps.setString(4, d.docType);
        setNullableString(ps, 5, d.module);
    }

    private static void bindSemanticAnnotation(PreparedStatement ps, SemanticAnnotation sa) throws SQLException {
        setNullableString(ps, 1, sa.nodeId);
        setNullableInt(ps, 2, sa.docId);
        setNullableString(ps, 3, sa.category);
        setNullableString(ps, 4, sa.businessLabel);
        setNullableString(ps, 5, sa.businessDescription);
        setNullableString(ps, 6, sa.domainContext);
        ps.setString(7, sa.source);
        ps.setString(8, sa.confidence);
    }

    private static void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) ps.setNull(index, Types.VARCHAR);
        else ps.setString(index, value);
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) ps.setNull(index, Types.INTEGER);
        else ps.setInt(index, value);
    }
}
