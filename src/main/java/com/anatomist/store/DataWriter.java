package com.anatomist.store;

import com.anatomist.model.Annotation;
import com.anatomist.model.ArchRole;
import com.anatomist.model.Document;
import com.anatomist.model.Edge;
import com.anatomist.model.FileCacheEntry;
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
        } catch (SQLException e) {
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
        String del = "DELETE FROM semantic_annotations WHERE node_id=? AND category=? AND source=?";
        String ins = "INSERT INTO semantic_annotations" +
                "(node_id,doc_id,category,business_label,business_description,domain_context,source,confidence)" +
                " VALUES (?,?,?,?,?,?,?,?)";
        inTransaction(c -> {
            try (PreparedStatement psDel = c.prepareStatement(del);
                 PreparedStatement psIns = c.prepareStatement(ins)) {
                for (SemanticAnnotation sa : sas) {
                    psDel.setString(1, sa.nodeId);
                    psDel.setString(2, sa.category);
                    psDel.setString(3, sa.source);
                    psDel.executeUpdate();

                    if (sa.nodeId == null) psIns.setNull(1, Types.VARCHAR); else psIns.setString(1, sa.nodeId);
                    if (sa.docId == null) psIns.setNull(2, Types.INTEGER); else psIns.setInt(2, sa.docId);
                    if (sa.category == null) psIns.setNull(3, Types.VARCHAR); else psIns.setString(3, sa.category);
                    if (sa.businessLabel == null) psIns.setNull(4, Types.VARCHAR); else psIns.setString(4, sa.businessLabel);
                    if (sa.businessDescription == null) psIns.setNull(5, Types.VARCHAR); else psIns.setString(5, sa.businessDescription);
                    if (sa.domainContext == null) psIns.setNull(6, Types.VARCHAR); else psIns.setString(6, sa.domainContext);
                    psIns.setString(7, sa.source);
                    psIns.setString(8, sa.confidence);
                    psIns.executeUpdate();
                }
            }
        });
    }

    public void upsertArchRoles(List<ArchRole> roles) {
        if (roles == null || roles.isEmpty()) return;
        String sql = "INSERT INTO arch_roles(node_id,role,confidence,source) VALUES (?,?,?,?)" +
                " ON CONFLICT(node_id) DO UPDATE SET role=excluded.role, confidence=excluded.confidence, source=excluded.source";
        inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (ArchRole r : roles) {
                    ps.setString(1, r.nodeId);
                    ps.setString(2, r.role);
                    ps.setString(3, r.confidence);
                    ps.setString(4, r.source);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    public void insertDocuments(List<Document> docs) {
        if (docs == null || docs.isEmpty()) return;
        String sql = "INSERT INTO documents(path,title,content,doc_type,module) VALUES (?,?,?,?,?)";
        inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (Document d : docs) {
                    ps.setString(1, d.path);
                    if (d.title == null) ps.setNull(2, Types.VARCHAR); else ps.setString(2, d.title);
                    if (d.content == null) ps.setNull(3, Types.VARCHAR); else ps.setString(3, d.content);
                    ps.setString(4, d.docType);
                    if (d.module == null) ps.setNull(5, Types.VARCHAR); else ps.setString(5, d.module);
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
        String sql = "INSERT OR REPLACE INTO file_cache" +
                "(source_file,hash,schema_version,last_indexed,node_count,edge_count)" +
                " VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
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
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO project_meta(key,value) VALUES (?,?) " +
                        "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            if (value == null) ps.setNull(2, Types.VARCHAR); else ps.setString(2, value);
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
        String delSem = "DELETE FROM semantic_annotations WHERE node_id IN (SELECT id FROM nodes WHERE source_file=?)";
        String delFc = "DELETE FROM file_cache WHERE source_file=?";
        String delNodes = "DELETE FROM nodes WHERE source_file=?";
        try (PreparedStatement psSem = c.prepareStatement(delSem);
             PreparedStatement psFc = c.prepareStatement(delFc);
             PreparedStatement psNodes = c.prepareStatement(delNodes)) {
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
            st.execute("DELETE FROM edges WHERE relation='WIRES'");
            st.execute("DELETE FROM nodes WHERE kind='BEAN'");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete spring bean graph", e);
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
            st.execute("DELETE FROM file_dependencies");
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
            st.execute(
                    "INSERT OR IGNORE INTO file_dependencies(source_file, depends_on_file) " +
                            "SELECT DISTINCT sn.source_file, tn.source_file " +
                            "FROM edges e " +
                            "JOIN nodes sn ON e.source_id = sn.id " +
                            "JOIN nodes tn ON e.target_id = tn.id " +
                            "WHERE e.is_external=0 " +
                            "AND sn.source_file IS NOT NULL AND tn.source_file IS NOT NULL " +
                            "AND sn.source_file <> tn.source_file"
            );
        } catch (SQLException e) {
            throw new RuntimeException("Failed to derive file_dependencies", e);
        }
    }

    // ── Package-private insert methods (used by SqliteStore.write()) ──

    static void insertNodes(Connection c, List<Node> nodes) throws SQLException {
        if (nodes == null || nodes.isEmpty()) return;
        String sql = "INSERT OR REPLACE INTO nodes" +
                "(id,label,kind,qualified_name,package,source_file,source_location,module,scope,javadoc,metadata)" +
                " VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Node n : nodes) {
                ps.setString(1, n.id);
                ps.setString(2, n.label);
                ps.setString(3, n.kind);
                ps.setString(4, n.qualifiedName);
                ps.setString(5, n.pkg);
                ps.setString(6, n.sourceFile == null ? "" : n.sourceFile);
                ps.setString(7, n.sourceLocation);
                ps.setString(8, n.module);
                ps.setString(9, n.scope == null ? "MAIN" : n.scope);
                ps.setString(10, n.javadoc);
                ps.setString(11, n.metadata);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    static void insertEdges(Connection c, List<Edge> edges) throws SQLException {
        if (edges == null || edges.isEmpty()) return;
        String sql = "INSERT INTO edges" +
                "(source_id,target_id,external_target_fqn,relation,call_kind,confidence,context,is_external,source_file,source_location,metadata)" +
                " VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Edge e : edges) {
                ps.setString(1, e.sourceId);
                if (e.targetId == null) ps.setNull(2, Types.VARCHAR); else ps.setString(2, e.targetId);
                if (e.externalTargetFqn == null) ps.setNull(3, Types.VARCHAR); else ps.setString(3, e.externalTargetFqn);
                ps.setString(4, e.relation);
                if (e.callKind == null) ps.setNull(5, Types.VARCHAR); else ps.setString(5, e.callKind);
                ps.setString(6, e.confidence == null ? "EXTRACTED" : e.confidence);
                if (e.context == null) ps.setNull(7, Types.VARCHAR); else ps.setString(7, e.context);
                ps.setInt(8, e.isExternal ? 1 : 0);
                ps.setString(9, e.sourceFile);
                ps.setString(10, e.sourceLocation);
                ps.setString(11, e.metadata);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    static void insertAnnotations(Connection c, List<Annotation> anns) throws SQLException {
        if (anns == null || anns.isEmpty()) return;
        String sql = "INSERT INTO annotations(node_id,annotation_fqn,attributes) VALUES (?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
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
        String sql = "INSERT INTO semantic_annotations" +
                "(node_id,doc_id,category,business_label,business_description,domain_context,source,confidence)" +
                " VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (SemanticAnnotation sa : sas) {
                if (sa.nodeId == null) ps.setNull(1, Types.VARCHAR); else ps.setString(1, sa.nodeId);
                if (sa.docId == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, sa.docId);
                if (sa.category == null) ps.setNull(3, Types.VARCHAR); else ps.setString(3, sa.category);
                if (sa.businessLabel == null) ps.setNull(4, Types.VARCHAR); else ps.setString(4, sa.businessLabel);
                if (sa.businessDescription == null) ps.setNull(5, Types.VARCHAR); else ps.setString(5, sa.businessDescription);
                if (sa.domainContext == null) ps.setNull(6, Types.VARCHAR); else ps.setString(6, sa.domainContext);
                ps.setString(7, sa.source);
                ps.setString(8, sa.confidence);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
