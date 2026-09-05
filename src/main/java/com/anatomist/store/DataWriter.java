package com.anatomist.store;

import com.anatomist.core.AnalysisCoverage;
import com.anatomist.core.ResolutionDiagnostics;
import com.anatomist.core.IndexDiagnostic;
import com.anatomist.model.Annotation;
import com.anatomist.model.AnnotationMetaRelation;
import com.anatomist.model.Document;
import com.anatomist.model.Declaration;
import com.anatomist.model.Edge;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;
import com.anatomist.model.ProducerIds;
import com.anatomist.model.SemanticAnnotation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class DataWriter {
    private static final String SQL_DELETE_SEMANTIC_ANNOTATION_BY_KEY =
            "DELETE FROM semantic_annotations WHERE node_id=? AND category=? AND source=? AND producer_id=?";
    private static final String SQL_INSERT_SEMANTIC_ANNOTATION =
            "INSERT INTO semantic_annotations"
                    + "(node_id,doc_id,category,business_label,business_description,domain_context,source,confidence,source_file,producer_id)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?)";
    private static final String SQL_INSERT_DOCUMENT =
            "INSERT INTO documents(path,title,content,doc_type,module) VALUES (?,?,?,?,?)";
    private static final String SQL_INSERT_FILE_CACHE =
            "INSERT OR REPLACE INTO file_cache"
                    + "(source_file,hash,schema_version,last_indexed,node_count,edge_count,"
                    + "file_size,file_mtime_ns,contract_hash) VALUES (?,?,?,?,?,?,?,?,?)";
    private static final String SQL_UPSERT_PROJECT_META =
            "INSERT INTO project_meta(key,value) VALUES (?,?) "
                    + "ON CONFLICT(key) DO UPDATE SET value=excluded.value "
                    + "WHERE project_meta.value IS NOT excluded.value";
    private static final String SQL_DELETE_SEMANTIC_ANNOTATIONS_BY_SOURCE_FILE =
            "DELETE FROM semantic_annotations WHERE source_file=? OR node_id IN (SELECT id FROM nodes WHERE source_file=?)";
    private static final String SQL_DELETE_FILE_CACHE_BY_SOURCE_FILE =
            "DELETE FROM file_cache WHERE source_file=?";
    private static final String SQL_DELETE_NODES_BY_SOURCE_FILE =
            "DELETE FROM nodes WHERE source_file=?";
    private static final String SQL_DELETE_WIRING_EDGES =
            "DELETE FROM edges WHERE producer_id='" + ProducerIds.DERIVED_WIRING + "'";
    private static final String SQL_DELETE_GENERATED_WIRING_EDGES =
            "DELETE FROM edges WHERE producer_id='" + ProducerIds.DERIVED_WIRING + "'";
    private static final String SQL_DELETE_XML_BEAN_GRAPH =
            "DELETE FROM nodes WHERE producer_id='" + ProducerIds.SPRING_XML + "'";
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
            UNION
            SELECT DISTINCT a.source_file, tn.source_file
            FROM annotations a
            JOIN nodes tn ON tn.qualified_name = a.annotation_fqn
            WHERE a.source_file IS NOT NULL AND tn.source_file IS NOT NULL
            AND a.source_file <> tn.source_file
            UNION
            SELECT DISTINCT am.source_file, tn.source_file
            FROM annotation_meta_relations am
            JOIN nodes tn ON tn.qualified_name = am.meta_annotation_fqn
            WHERE am.source_file IS NOT NULL AND tn.source_file IS NOT NULL
            AND am.source_file <> tn.source_file
            """;
    private static final String SQL_INSERT_NODE =
            "INSERT INTO nodes"
                    + "(id,symbol_id,label,kind,qualified_name,package,source_file,source_location,begin_line,begin_column,end_line,end_column,source_ordinal,module,scope,javadoc,metadata,producer_id)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                    + " ON CONFLICT(id) DO UPDATE SET"
                    + " symbol_id=excluded.symbol_id,label=excluded.label,kind=excluded.kind,"
                    + " qualified_name=excluded.qualified_name,package=excluded.package,"
                    + " source_file=excluded.source_file,source_location=excluded.source_location,"
                    + " begin_line=excluded.begin_line,begin_column=excluded.begin_column,"
                    + " end_line=excluded.end_line,end_column=excluded.end_column,source_ordinal=excluded.source_ordinal,"
                    + " module=excluded.module,scope=excluded.scope,javadoc=excluded.javadoc,"
                    + " metadata=excluded.metadata,producer_id=excluded.producer_id";
    private static final String SQL_INSERT_EDGE =
            "INSERT INTO edges"
                    + "(source_id,target_id,external_target_fqn,relation,call_kind,confidence,resolution,context,is_external,"
                    + "source_file,source_location,begin_line,begin_column,end_line,end_column,source_ordinal,"
                    + "syntax_target,receiver_static_type,metadata,producer_id)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String SQL_INSERT_ANNOTATION =
            "INSERT INTO annotations(node_id,annotation_fqn,raw_name,attributes,target_kind,target_path,"
                    + "language,mechanism,resolution_status,source_file,source_location,begin_line,begin_column,"
                    + "end_line,end_column,producer_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String SQL_INSERT_ANNOTATION_META =
            "INSERT OR IGNORE INTO annotation_meta_relations(annotation_fqn,meta_annotation_fqn,raw_name,"
                    + "language,mechanism,resolution_status,source_file,source_location,producer_id)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)";
    private static final String SQL_INSERT_DECLARATION = "INSERT OR REPLACE INTO declarations(symbol_id,"
            + "qualified_name,label,kind,declaration_kind,type_kind,visibility,modifiers,declared_modifiers,"
            + "implicit_modifiers,declaring_type,source_file,source_location,begin_line,begin_column,end_line,end_column,"
            + "module,scope,nesting_depth,direct_member,synthetic,binding_resolved,producer_id) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

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
            insertAnnotationMetaRelations(c, result.annotationMetaRelations);
            insertSemanticAnnotations(c, result.semanticAnnotations);
            insertDeclarations(c, result.declarations);
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
                    psDel.setString(4, producer(sa.producerId, ProducerIds.MANUAL_ANNOTATION));
                    psDel.executeUpdate();

                    bindSemanticAnnotation(psIns, sa);
                    psIns.executeUpdate();
                }
            }
            IndexRevision.bump(c);
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

    /** Replace all documents and establish their canonical project identity atomically. */
    public void replaceDocumentsForProject(List<Document> docs, String sourceRoot) {
        inTransaction(c -> {
            try (PreparedStatement meta = c.prepareStatement(SQL_UPSERT_PROJECT_META)) {
                meta.setString(1, "source_root");
                setNullableString(meta, 2, sourceRoot);
                meta.executeUpdate();
            }
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM documents");
            }
            if (docs != null && !docs.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_DOCUMENT)) {
                    for (Document d : docs) {
                        bindDocument(ps, d);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            IndexRevision.bump(c);
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
                ps.setLong(7, entry.fileSize());
                ps.setLong(8, entry.fileMtimeNs());
                ps.setString(9, entry.contractHash() == null ? "" : entry.contractHash());
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

    /** Atomically write one coherent metadata snapshot, skipping unchanged values. */
    public void upsertProjectMeta(Map<String, String> values) {
        if (values == null || values.isEmpty()) return;
        inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(SQL_UPSERT_PROJECT_META)) {
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    ps.setString(1, entry.getKey());
                    setNullableString(ps, 2, entry.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
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
             PreparedStatement psMeta = c.prepareStatement(
                     "DELETE FROM annotation_meta_relations WHERE source_file=?");
             PreparedStatement psFc = c.prepareStatement(SQL_DELETE_FILE_CACHE_BY_SOURCE_FILE);
             PreparedStatement psDecl = c.prepareStatement("DELETE FROM declarations WHERE source_file=?");
             PreparedStatement psNodes = c.prepareStatement(SQL_DELETE_NODES_BY_SOURCE_FILE)) {
            for (String f : sourceFiles) {
                psSem.setString(1, f); psSem.setString(2, f); psSem.addBatch();
                psMeta.setString(1, f); psMeta.addBatch();
                psNodes.setString(1, f); psNodes.addBatch();
                psFc.setString(1, f); psFc.addBatch();
                psDecl.setString(1, f); psDecl.addBatch();
            }
            psSem.executeBatch();
            psMeta.executeBatch();
            psNodes.executeBatch();
            psFc.executeBatch();
            psDecl.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete by source files", e);
        }
    }

    /**
     * Replace facts owned by source files while updating stable node ids in place.
     * Incoming edges from other files survive unless their exact target node disappeared.
     */
    public ReplacementStats replaceSourceGraphInCurrentTransaction(
            List<String> sourceFiles, com.anatomist.model.ExtractionResult result) {
        if (sourceFiles == null || sourceFiles.isEmpty()) return new ReplacementStats(0, 0);
        try {
            Connection c = connSupplier.get();
            Set<String> oldIds = nodeIdsBySourceFiles(c, sourceFiles);
            Set<String> newIds = new HashSet<>();
            if (result != null) {
                for (Node node : result.nodes) newIds.add(node.id);
            }
            Set<String> obsoleteIds = new LinkedHashSet<>(oldIds);
            obsoleteIds.removeAll(newIds);

            int deletedEdges = countSourceOwnedAndObsoleteTargetEdges(c, sourceFiles, obsoleteIds);
            deleteSourceOwnedFacts(c, sourceFiles);
            if (result != null) insertNodes(c, result.nodes);
            deleteNodes(c, obsoleteIds);
            if (result != null) {
                insertEdges(c, result.edges);
                insertAnnotations(c, result.annotations);
                insertAnnotationMetaRelations(c, result.annotationMetaRelations);
                insertSemanticAnnotations(c, result.semanticAnnotations);
                insertDeclarations(c, result.declarations);
            }
            return new ReplacementStats(obsoleteIds.size(), deletedEdges);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to replace source graph", e);
        }
    }

    private static Set<String> nodeIdsBySourceFiles(Connection c, List<String> sourceFiles)
            throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM nodes WHERE source_file=?")) {
            for (String sourceFile : sourceFiles) {
                ps.setString(1, sourceFile);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private static int countSourceOwnedAndObsoleteTargetEdges(
            Connection c, List<String> sourceFiles, Set<String> obsoleteIds) throws SQLException {
        Set<Long> edgeIds = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM edges WHERE source_file=? "
                        + "OR source_id IN (SELECT id FROM nodes WHERE source_file=?)")) {
            for (String sourceFile : sourceFiles) {
                ps.setString(1, sourceFile);
                ps.setString(2, sourceFile);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) edgeIds.add(rs.getLong(1));
                }
            }
        }
        if (!obsoleteIds.isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM edges WHERE target_id=?")) {
                for (String nodeId : obsoleteIds) {
                    ps.setString(1, nodeId);
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) edgeIds.add(rs.getLong(1));
                    }
                }
            }
        }
        return edgeIds.size();
    }

    private static void deleteSourceOwnedFacts(Connection c, List<String> sourceFiles)
            throws SQLException {
        try (PreparedStatement semantic = c.prepareStatement(
                    "DELETE FROM semantic_annotations WHERE node_id IN (SELECT id FROM nodes WHERE source_file=?)");
             PreparedStatement annotations = c.prepareStatement(
                    "DELETE FROM annotations WHERE node_id IN (SELECT id FROM nodes WHERE source_file=?)");
             PreparedStatement annotationMeta = c.prepareStatement(
                    "DELETE FROM annotation_meta_relations WHERE source_file=?");
             PreparedStatement edges = c.prepareStatement(
                    "DELETE FROM edges WHERE source_file=? "
                            + "OR source_id IN (SELECT id FROM nodes WHERE source_file=?)");
             PreparedStatement declarations = c.prepareStatement(
                    "DELETE FROM declarations WHERE source_file=?");
             PreparedStatement cache = c.prepareStatement("DELETE FROM file_cache WHERE source_file=?")) {
            for (String sourceFile : sourceFiles) {
                semantic.setString(1, sourceFile); semantic.addBatch();
                annotations.setString(1, sourceFile); annotations.addBatch();
                annotationMeta.setString(1, sourceFile); annotationMeta.addBatch();
                edges.setString(1, sourceFile); edges.setString(2, sourceFile); edges.addBatch();
                cache.setString(1, sourceFile); cache.addBatch();
                declarations.setString(1, sourceFile); declarations.addBatch();
            }
            semantic.executeBatch();
            annotations.executeBatch();
            annotationMeta.executeBatch();
            edges.executeBatch();
            cache.executeBatch();
            declarations.executeBatch();
        }
    }

    private static void deleteNodes(Connection c, Set<String> nodeIds) throws SQLException {
        if (nodeIds.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM nodes WHERE id=?")) {
            for (String id : nodeIds) {
                ps.setString(1, id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public record ReplacementStats(int deletedNodes, int deletedEdges) {}

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

    /** Refresh only dependency rows incident to files rewritten by an incremental batch. */
    public void refreshFileDependencies(List<String> affectedFiles) {
        if (affectedFiles == null || affectedFiles.isEmpty()) return;
        Connection c;
        try {
            c = connSupplier.get();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire SQLite connection", e);
        }
        String placeholders = String.join(",", Collections.nCopies(affectedFiles.size(), "?"));
        String deleteSql = "DELETE FROM file_dependencies WHERE source_file IN (" + placeholders
                + ") OR depends_on_file IN (" + placeholders + ")";
        String deriveSql = """
                INSERT OR IGNORE INTO file_dependencies(source_file, depends_on_file)
                SELECT sn.source_file, tn.source_file
                FROM nodes sn
                JOIN edges e ON e.source_id = sn.id
                JOIN nodes tn ON tn.id = e.target_id
                WHERE e.is_external=0
                AND sn.source_file IS NOT NULL AND tn.source_file IS NOT NULL
                AND sn.source_file <> tn.source_file
                AND sn.source_file IN (%s)
                UNION
                SELECT sn.source_file, tn.source_file
                FROM nodes tn
                JOIN edges e ON e.target_id = tn.id
                JOIN nodes sn ON sn.id = e.source_id
                WHERE e.is_external=0
                AND sn.source_file IS NOT NULL AND tn.source_file IS NOT NULL
                AND sn.source_file <> tn.source_file
                AND tn.source_file IN (%s)
                UNION
                SELECT a.source_file, tn.source_file
                FROM annotations a
                JOIN nodes tn ON tn.qualified_name = a.annotation_fqn
                WHERE a.source_file IS NOT NULL AND tn.source_file IS NOT NULL
                AND a.source_file <> tn.source_file
                AND a.source_file IN (%s)
                UNION
                SELECT a.source_file, tn.source_file
                FROM annotations a
                JOIN nodes tn ON tn.qualified_name = a.annotation_fqn
                WHERE a.source_file IS NOT NULL AND tn.source_file IS NOT NULL
                AND a.source_file <> tn.source_file
                AND tn.source_file IN (%s)
                UNION
                SELECT am.source_file, tn.source_file
                FROM annotation_meta_relations am
                JOIN nodes tn ON tn.qualified_name = am.meta_annotation_fqn
                WHERE am.source_file IS NOT NULL AND tn.source_file IS NOT NULL
                AND am.source_file <> tn.source_file
                AND am.source_file IN (%s)
                UNION
                SELECT am.source_file, tn.source_file
                FROM annotation_meta_relations am
                JOIN nodes tn ON tn.qualified_name = am.meta_annotation_fqn
                WHERE am.source_file IS NOT NULL AND tn.source_file IS NOT NULL
                AND am.source_file <> tn.source_file
                AND tn.source_file IN (%s)
                """.formatted(placeholders, placeholders, placeholders,
                        placeholders, placeholders, placeholders);
        try (PreparedStatement delete = c.prepareStatement(deleteSql);
             PreparedStatement derive = c.prepareStatement(deriveSql)) {
            bindRepeated(delete, affectedFiles, 2);
            delete.executeUpdate();
            bindRepeated(derive, affectedFiles, 6);
            derive.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to refresh incremental file_dependencies", e);
        }
    }

    private static void bindRepeated(PreparedStatement statement,
                                     List<String> values,
                                     int repetitions) throws SQLException {
        int index = 1;
        for (int repeat = 0; repeat < repetitions; repeat++) {
            for (String value : values) statement.setString(index++, value);
        }
    }

    public void replaceIndexDiagnostics(List<IndexDiagnostic> diagnostics) {
        inTransaction(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM index_diagnostics");
                st.execute("DELETE FROM analysis_coverage");
            }
            insertIndexDiagnostics(c, diagnostics);
            insertAnalysisCoverage(c, AnalysisCoverage.summarize(diagnostics));
        });
    }

    public void replaceAnalysisCoverage(List<IndexDiagnostic> diagnostics) {
        inTransaction(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM analysis_coverage");
            }
            insertAnalysisCoverage(c, AnalysisCoverage.summarize(diagnostics));
        });
    }

    public void replaceIndexDiagnosticsForFiles(List<String> sourceFiles,
                                                List<IndexDiagnostic> diagnostics) {
        if (sourceFiles == null || sourceFiles.isEmpty()) return;
        inTransaction(c -> {
            String placeholders = String.join(",", java.util.Collections.nCopies(
                    sourceFiles.size(), "?"));
            try (PreparedStatement delete = c.prepareStatement(
                    "DELETE FROM index_diagnostics WHERE source_file IN (" + placeholders + ")")) {
                for (int i = 0; i < sourceFiles.size(); i++) {
                    delete.setString(i + 1, sourceFiles.get(i));
                }
                delete.executeUpdate();
            }
            insertIndexDiagnostics(c, diagnostics);
            deleteAnalysisCoverageForFiles(c, sourceFiles);
            insertAnalysisCoverage(c, AnalysisCoverage.summarize(diagnostics));
            refreshUnresolvedAggregate(c);
        });
    }

    private static void deleteAnalysisCoverageForFiles(Connection c, List<String> sourceFiles)
            throws SQLException {
        String placeholders = String.join(",", java.util.Collections.nCopies(
                sourceFiles.size(), "?"));
        try (PreparedStatement delete = c.prepareStatement(
                "DELETE FROM analysis_coverage WHERE source_file IN (" + placeholders + ")")) {
            for (int i = 0; i < sourceFiles.size(); i++) {
                delete.setString(i + 1, sourceFiles.get(i));
            }
            delete.executeUpdate();
        }
    }

    private static void insertAnalysisCoverage(Connection c, List<AnalysisCoverage.Row> rows)
            throws SQLException {
        if (rows == null || rows.isEmpty()) return;
        String sql = """
                INSERT OR REPLACE INTO analysis_coverage
                (source_file,module,scope,capability,status,occurrences,groups_count,
                 codes,code_counts,details_truncated)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """;
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (AnalysisCoverage.Row row : rows) {
                statement.setString(1, row.sourceFile());
                statement.setString(2, row.module());
                statement.setString(3, row.scope());
                statement.setString(4, row.capability());
                statement.setString(5, row.status());
                statement.setLong(6, row.occurrences());
                statement.setLong(7, row.groups());
                statement.setString(8, row.codes());
                statement.setString(9, row.codeCounts());
                statement.setInt(10, row.detailsTruncated() ? 1 : 0);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void refreshUnresolvedAggregate(Connection c) throws SQLException {
        try (PreparedStatement delete = c.prepareStatement(
                "DELETE FROM index_diagnostics WHERE code='UNRESOLVED_SYMBOLS'")) {
            delete.executeUpdate();
        }
        String reasons = ResolutionDiagnostics.REASON_CODES.stream().sorted()
                .map(code -> "'" + code + "'")
                .collect(java.util.stream.Collectors.joining(","));
        long unresolved;
        try (Statement statement = c.createStatement();
             java.sql.ResultSet result = statement.executeQuery(
                     "SELECT COALESCE(SUM(occurrence_count),0) FROM index_diagnostics "
                             + "WHERE code IN (" + reasons + ")")) {
            unresolved = result.next() ? result.getLong(1) : 0L;
        }
        if (unresolved > 0) {
            insertIndexDiagnostics(c, List.of(new IndexDiagnostic(
                    "info", "UNRESOLVED_SYMBOLS", "RESOLUTION",
                    null, null, null, null, unresolved,
                    "Aggregate of persisted file/reason/scope resolution diagnostics.")));
        }
    }

    private static void insertIndexDiagnostics(Connection c,
                                               List<IndexDiagnostic> diagnostics)
            throws SQLException {
        if (diagnostics == null || diagnostics.isEmpty()) return;
        List<IndexDiagnostic> ordered =
                com.anatomist.core.IndexDiagnosticRetention.retain(diagnostics);
        String sql = "INSERT INTO index_diagnostics(severity,code,phase,source_file,module,scope,symbol,occurrence_count,sample)"
                + " VALUES (?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (IndexDiagnostic d : ordered) {
                ps.setString(1, d.severity());
                ps.setString(2, d.code());
                ps.setString(3, d.phase());
                setNullableString(ps, 4, d.sourceFile());
                setNullableString(ps, 5, d.module());
                setNullableString(ps, 6, d.scope());
                setNullableString(ps, 7, d.symbol());
                ps.setLong(8, d.count());
                setNullableString(ps, 9, d.sample() == null ? null
                        : d.sample().substring(0, Math.min(500, d.sample().length())));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── Package-private insert methods (used by SqliteStore.write()) ──

    static void insertNodes(Connection c, List<Node> nodes) throws SQLException {
        if (nodes == null || nodes.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_NODE)) {
            for (Node n : nodes) {
                ps.setString(1, n.id);
                ps.setString(2, n.symbolId == null ? n.id : n.symbolId);
                ps.setString(3, n.label);
                ps.setString(4, n.kind);
                ps.setString(5, n.qualifiedName);
                ps.setString(6, n.pkg);
                ps.setString(7, n.sourceFile == null ? "" : n.sourceFile);
                ps.setString(8, n.sourceLocation);
                setNullableInt(ps, 9, n.beginLine);
                setNullableInt(ps, 10, n.beginColumn);
                setNullableInt(ps, 11, n.endLine);
                setNullableInt(ps, 12, n.endColumn);
                setNullableInt(ps, 13, n.sourceOrdinal);
                ps.setString(14, n.module == null ? "." : n.module);
                ps.setString(15, n.scope == null ? GraphConstants.Scope.MAIN : n.scope);
                ps.setString(16, n.javadoc);
                ps.setString(17, n.metadata);
                ps.setString(18, producer(n.producerId, ProducerIds.JAVA_CORE));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    static void insertEdges(Connection c, List<Edge> edges) throws SQLException {
        if (edges == null || edges.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_EDGE)) {
            for (Edge e : edges) {
                com.anatomist.model.EdgeTarget target = e.target();
                String internalTarget = switch (target) {
                    case com.anatomist.model.EdgeTarget.Internal internal -> internal.nodeId();
                    case com.anatomist.model.EdgeTarget.External ignored -> null;
                };
                String externalTarget = switch (target) {
                    case com.anatomist.model.EdgeTarget.Internal ignored -> null;
                    case com.anatomist.model.EdgeTarget.External external -> external.fqn();
                };
                String resolution = switch (target) {
                    case com.anatomist.model.EdgeTarget.Internal ignored -> null;
                    case com.anatomist.model.EdgeTarget.External external -> external.resolution();
                };
                ps.setString(1, e.sourceId);
                setNullableString(ps, 2, internalTarget);
                setNullableString(ps, 3, externalTarget);
                ps.setString(4, e.relation);
                setNullableString(ps, 5, e.callKind);
                ps.setString(6, e.confidence == null ? GraphConstants.Confidence.EXTRACTED : e.confidence);
                setNullableString(ps, 7, resolution);
                setNullableString(ps, 8, e.context);
                ps.setInt(9, target instanceof com.anatomist.model.EdgeTarget.External ? 1 : 0);
                ps.setString(10, e.sourceFile);
                ps.setString(11, e.sourceLocation);
                setNullableInt(ps, 12, e.beginLine);
                setNullableInt(ps, 13, e.beginColumn);
                setNullableInt(ps, 14, e.endLine);
                setNullableInt(ps, 15, e.endColumn);
                setNullableInt(ps, 16, e.sourceOrdinal);
                setNullableString(ps, 17, e.syntaxTarget);
                setNullableString(ps, 18, e.receiverStaticType);
                ps.setString(19, e.metadata);
                ps.setString(20, producer(e.producerId, ProducerIds.JAVA_CORE));
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
                int i = 1;
                ps.setString(i++, a.nodeId);
                ps.setString(i++, a.annotationFqn);
                ps.setString(i++, a.rawName == null ? a.annotationFqn : a.rawName);
                ps.setString(i++, a.attributes);
                ps.setString(i++, a.targetKind == null ? "entity" : a.targetKind);
                ps.setString(i++, a.targetPath);
                ps.setString(i++, a.language == null ? "java" : a.language);
                ps.setString(i++, a.mechanism == null ? "java.annotation" : a.mechanism);
                ps.setString(i++, a.resolutionStatus == null ? "exact" : a.resolutionStatus);
                ps.setString(i++, a.sourceFile);
                ps.setString(i++, a.sourceLocation);
                setNullableInt(ps, i++, a.beginLine);
                setNullableInt(ps, i++, a.beginColumn);
                setNullableInt(ps, i++, a.endLine);
                setNullableInt(ps, i++, a.endColumn);
                ps.setString(i, producer(a.producerId, ProducerIds.JAVA_CORE));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    static void insertAnnotationMetaRelations(Connection c, List<AnnotationMetaRelation> relations)
            throws SQLException {
        if (relations == null || relations.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_ANNOTATION_META)) {
            for (AnnotationMetaRelation relation : relations) {
                int i = 1;
                ps.setString(i++, relation.annotationFqn);
                ps.setString(i++, relation.metaAnnotationFqn);
                ps.setString(i++, relation.rawName == null
                        ? relation.metaAnnotationFqn : relation.rawName);
                ps.setString(i++, relation.language == null ? "java" : relation.language);
                ps.setString(i++, relation.mechanism == null
                        ? "java.annotation.meta" : relation.mechanism);
                ps.setString(i++, relation.resolutionStatus == null
                        ? "exact" : relation.resolutionStatus);
                ps.setString(i++, relation.sourceFile);
                ps.setString(i++, relation.sourceLocation);
                ps.setString(i, producer(relation.producerId, ProducerIds.JAVA_CORE));
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

    static void insertDeclarations(Connection c, List<Declaration> declarations) throws SQLException {
        if (declarations == null || declarations.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_DECLARATION)) {
            for (Declaration declaration : declarations) {
                int i = 1;
                ps.setString(i++, declaration.symbolId); ps.setString(i++, declaration.qualifiedName);
                ps.setString(i++, declaration.label); ps.setString(i++, declaration.kind);
                ps.setString(i++, declaration.declarationKind); ps.setString(i++, declaration.typeKind);
                ps.setString(i++, declaration.visibility);
                ps.setString(i++, com.anatomist.json.Json.writeCompact(declaration.modifiers));
                ps.setString(i++, com.anatomist.json.Json.writeCompact(declaration.declaredModifiers));
                ps.setString(i++, com.anatomist.json.Json.writeCompact(declaration.implicitModifiers));
                ps.setString(i++, declaration.declaringType); ps.setString(i++, declaration.sourceFile);
                ps.setString(i++, declaration.sourceLocation);
                setNullableInt(ps, i++, declaration.beginLine); setNullableInt(ps, i++, declaration.beginColumn);
                setNullableInt(ps, i++, declaration.endLine); setNullableInt(ps, i++, declaration.endColumn);
                ps.setString(i++, declaration.module == null ? "." : declaration.module);
                ps.setString(i++, declaration.scope == null ? GraphConstants.Scope.MAIN : declaration.scope);
                ps.setInt(i++, declaration.nestingDepth);
                ps.setInt(i++, declaration.directMember ? 1 : 0); ps.setInt(i++, declaration.synthetic ? 1 : 0);
                ps.setInt(i++, declaration.bindingResolved ? 1 : 0);
                ps.setString(i, producer(declaration.producerId, ProducerIds.JAVA_CORE)); ps.addBatch();
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
        setNullableString(ps, 9, sa.sourceFile);
        ps.setString(10, producer(sa.producerId, ProducerIds.MANUAL_ANNOTATION));
    }

    private static String producer(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
