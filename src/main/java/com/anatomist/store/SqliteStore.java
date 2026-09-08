package com.anatomist.store;

import com.anatomist.core.IndexDiagnostic;
import com.anatomist.model.Annotation;
import com.anatomist.model.Document;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.model.Node;
import com.anatomist.model.SemanticAnnotation;
import com.anatomist.model.SymbolFact;
import com.anatomist.model.TypeRelationFact;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class SqliteStore implements IndexWriter {

    private final Path dbPath;
    private Connection connection;

    private final SchemaManager schema;
    private final DataWriter writer;
    private final DataReader reader;

    public SqliteStore(Path dbPath) {
        this.dbPath = dbPath;
        ConnectionSupplier supplier = this::connection;
        this.schema = new SchemaManager(supplier);
        this.writer = new DataWriter(supplier);
        this.reader = new DataReader(supplier);
    }

    public Path dbPath() {
        return dbPath;
    }

    public synchronized Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            String url = "jdbc:sqlite:" + dbPath.toString();
            connection = DriverManager.getConnection(url);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA journal_mode = WAL");
                st.execute("PRAGMA synchronous = NORMAL");
                st.execute("PRAGMA cache_size = -64000");
                st.execute("PRAGMA temp_store = MEMORY");
                Path actual=dbPath.toRealPath();
                if(java.nio.file.Files.exists(actual.resolveSibling(actual.getFileName()+".snapshot")))
                    st.execute("PRAGMA query_only=ON");
            }
            catch (java.io.IOException failure) { throw new SQLException("Cannot resolve index path",failure); }
        }
        return connection;
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                if (!connection.isClosed()) connection.close();
            } catch (SQLException ignore) {
            } finally {
                connection = null;
            }
        }
    }

    // ── Schema ──────────────────────────────────────────────────────────

    public void initSchema() { schema.initSchema(); }

    /**
     * Full indexes are built at a disposable path and atomically published only after validation.
     * Avoid durable journaling for that unpublished copy; reopening the promoted DB restores WAL/NORMAL.
     */
    public void prepareDisposableBuild() {
        try (Statement statement = connection().createStatement()) {
            statement.execute("PRAGMA journal_mode=OFF");
            statement.execute("PRAGMA synchronous=OFF");
            statement.execute("PRAGMA locking_mode=EXCLUSIVE");
        } catch (SQLException failure) {
            throw new RuntimeException("Failed to prepare disposable index build", failure);
        }
    }

    /** Restore the normal live-index durability profile before atomic publication. */
    public void finishDisposableBuild() {
        try (Statement statement = connection().createStatement()) {
            statement.execute("PRAGMA locking_mode=NORMAL");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException failure) {
            throw new RuntimeException("Failed to finalize disposable index build", failure);
        }
    }
    public boolean schemaExists() { return schema.schemaExists(); }
    public int schemaVersion() { return schema.schemaVersion(); }
    public boolean schemaCompatible() { return schema.schemaCompatible(); }
    public void clearAllData() { schema.clearAllData(); }

    // ── Write ───────────────────────────────────────────────────────────

    public void write(ExtractionResult result) {
        if (result == null) return;
        writer.inTransaction(c -> {
            DataWriter.insertNodes(c, result.nodes);
            DataWriter.insertEdges(c, result.edges);
            DataWriter.insertAnnotations(c, result.annotations);
            DataWriter.insertAnnotationMetaRelations(c, result.annotationMetaRelations);
            DataWriter.insertSemanticAnnotations(c, result.semanticAnnotations);
            DataWriter.applyDeclarations(c, result.declarations);
            CallSitePersistence.rebuild(c);
            IndexRevision.bump(c);
        });
        writer.runAnalyze();
    }

    public void writeNodes(List<Node> nodes) { writer.writeNodes(nodes); }
    public void writeInCurrentTransaction(ExtractionResult result) { writer.writeInCurrentTransaction(result); }
    public void writeEdgesBatched(List<Edge> edges, int batchSize) { writer.writeEdgesBatched(edges, batchSize); }
    public void writeAnnotationsBatched(List<Annotation> annotations, List<SemanticAnnotation> semanticAnnotations, int batchSize) {
        writer.writeAnnotationsBatched(annotations, semanticAnnotations, batchSize);
    }
    public void runAnalyze() { writer.runAnalyze(); }
    public void upsertSemanticAnnotation(SemanticAnnotation sa) { writer.upsertSemanticAnnotation(sa); }
    public void upsertSemanticAnnotations(List<SemanticAnnotation> sas) { writer.upsertSemanticAnnotations(sas); }
    public void insertDocuments(List<Document> docs) { writer.insertDocuments(docs); }
    public void replaceDocuments(List<Document> docs) { writer.replaceDocuments(docs); }
    public void replaceDocumentsForProject(List<Document> docs, String sourceRoot) {
        writer.replaceDocumentsForProject(docs, sourceRoot);
    }
    public void updateFileCache(List<FileCacheEntry> entries) { writer.updateFileCache(entries); }
    public void upsertProjectMeta(String key, String value) { writer.upsertProjectMeta(key, value); }
    public void upsertProjectMeta(Map<String, String> values) { writer.upsertProjectMeta(values); }
    public void deleteBySourceFiles(List<String> sourceFiles) { writer.deleteBySourceFiles(sourceFiles); }
    public DataWriter.ReplacementStats replaceSourceGraphInCurrentTransaction(
            List<String> sourceFiles, ExtractionResult result) {
        return writer.replaceSourceGraphInCurrentTransaction(sourceFiles, result);
    }
    public void deleteSpringBeanGraph() { writer.deleteSpringBeanGraph(); }
    public void replaceGeneratedWiringEdges(List<Edge> edges) { writer.replaceGeneratedWiringEdges(edges); }
    public void replaceGeneratedWiringEdgesInCurrentTransaction(List<Edge> edges) {
        writer.replaceGeneratedWiringEdgesInCurrentTransaction(edges);
    }
    public void clearFileDependencies() { writer.clearFileDependencies(); }
    public void deriveFileDependencies() { writer.deriveFileDependencies(); }
    public void refreshFileDependencies() { writer.refreshFileDependencies(); }
    public void refreshFileDependencies(List<String> affectedFiles) {
        writer.refreshFileDependencies(affectedFiles);
    }
    public void refreshSymbolDependencies() { writer.refreshSymbolDependencies(); }
    public void refreshSymbolDependencies(List<String> affectedFiles) {
        writer.refreshSymbolDependencies(affectedFiles);
    }
    public void replaceIndexDiagnostics(List<IndexDiagnostic> diagnostics) { writer.replaceIndexDiagnostics(diagnostics); }
    public void replaceAnalysisCoverage(List<IndexDiagnostic> diagnostics) {
        writer.replaceAnalysisCoverage(diagnostics);
    }
    public void replaceIndexDiagnosticsForFiles(List<String> sourceFiles,
                                                List<IndexDiagnostic> diagnostics) {
        writer.replaceIndexDiagnosticsForFiles(sourceFiles, diagnostics);
    }

    @FunctionalInterface
    public interface TxWork {
        void run(Connection c) throws java.sql.SQLException;
    }

    public void inTransaction(TxWork work) { writer.inTransaction(work::run); }

    // ── Read ────────────────────────────────────────────────────────────

    public Map<String, FileCacheEntry> readFileCache() { return reader.readFileCache(); }
    public Optional<String> readProjectMeta(String key) { return reader.readProjectMeta(key); }
    public Map<String, String> readProjectMeta() { return reader.readProjectMeta(); }
    public long countNodesByProducer(String producerId) {
        return reader.countNodesByProducer(producerId);
    }
    public Map<String, Long> readResolutionDiagnosticCounts() {
        return reader.readResolutionDiagnosticCounts();
    }
    public List<Map<String, Object>> readDiagnosticCoverage(String sourceFileFilter) {
        return reader.readDiagnosticCoverage(sourceFileFilter);
    }
    public Set<String> dependentsOf(List<String> seed) { return reader.dependentsOf(seed); }
    public Set<String> allNodeIds() { return reader.allNodeIds(); }
    public Set<String> nodeIdsForSymbols(Set<String> symbols) {
        return reader.nodeIdsForSymbols(symbols);
    }
    public Map<String, Node> readNodesBySourceFiles(List<String> sourceFiles) {
        return reader.readNodesBySourceFiles(sourceFiles);
    }
    public Set<String> sourceFilesReferencingNodeIds(Set<String> nodeIds) {
        return reader.sourceFilesReferencingNodeIds(nodeIds);
    }
    public Set<String> sourceFilesReferencingOwnerIds(Set<String> ownerIds) {
        return reader.sourceFilesReferencingOwnerIds(ownerIds);
    }
    public Set<String> sourceFilesMatchingExternalTargets(Set<String> logicalPrefixes) {
        return reader.sourceFilesMatchingExternalTargets(logicalPrefixes);
    }

    public Set<String> sourceFilesMatchingExactExternalTargets(Set<String> logicalTargets) {
        return reader.sourceFilesMatchingExactExternalTargets(logicalTargets);
    }

    public Set<String> sourceFilesImplementingTypeIds(Set<String> ownerIds) {
        return reader.sourceFilesImplementingTypeIds(ownerIds);
    }
    public Map<String, String> readBeanClassTargets() { return reader.readBeanClassTargets(); }
    public Map<String, FileCacheService.SourceFileStats> sourceFileStats() { return reader.sourceFileStats(); }
    public FileCacheService.SourceFileStats countRowsDeletedBySourceFiles(List<String> sourceFiles) {
        return reader.countRowsDeletedBySourceFiles(sourceFiles);
    }
    public FileCacheService.SourceFileStats countSpringBeanGraphRows() { return reader.countSpringBeanGraphRows(); }
    public int countGeneratedWiringEdges() { return reader.countGeneratedWiringEdges(); }
    public List<Edge> readWiringSourceEdges() { return reader.readWiringSourceEdges(); }
    public List<Edge> readWiringSourceEdgesBySourceFiles(List<String> sourceFiles) {
        return reader.readWiringSourceEdgesBySourceFiles(sourceFiles);
    }
    public int countGeneratedWiringEdgesBySourceFiles(List<String> sourceFiles) {
        return reader.countGeneratedWiringEdgesBySourceFiles(sourceFiles);
    }
    public Map<String, Long> queryKindCounts() { return reader.queryKindCounts(); }
    public Set<String> queryPackagesByKinds(Set<String> kinds) { return reader.queryPackagesByKinds(kinds); }
    public Map<String, Long> queryRelationCounts() { return reader.queryRelationCounts(); }
    public long queryAnnotationCount() { return reader.queryAnnotationCount(); }
    public long querySemanticAnnotationCount() { return reader.querySemanticAnnotationCount(); }
    public List<IndexDiagnostic> readIndexDiagnostics() { return reader.readIndexDiagnostics(); }
    public List<IndexDiagnostic> readIndexDiagnosticSummary() {
        return reader.readIndexDiagnosticSummary();
    }

    public List<SymbolFact> readSymbolFacts() {
        try (Statement statement = connection().createStatement();
             java.sql.ResultSet rows = statement.executeQuery(
                     "SELECT id,symbol_id,kind,module,scope,source_file,metadata FROM nodes")) {
            List<SymbolFact> out = new java.util.ArrayList<>();
            while (rows.next()) {
                out.add(new SymbolFact(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7)));
            }
            return List.copyOf(out);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read symbol facts", e);
        }
    }

    public List<TypeRelationFact> readTypeRelations() {
        String sql = "SELECT s.symbol_id,COALESCE(t.symbol_id,e.external_target_fqn),e.relation "
                + "FROM edges e JOIN nodes s ON s.id=e.source_id LEFT JOIN nodes t ON t.id=e.target_id "
                + "WHERE e.relation IN ('INHERITS','IMPLEMENTS')";
        try (Statement statement = connection().createStatement();
             java.sql.ResultSet rows = statement.executeQuery(sql)) {
            List<TypeRelationFact> out = new java.util.ArrayList<>();
            while (rows.next()) out.add(new TypeRelationFact(
                    rows.getString(1), rows.getString(2), rows.getString(3)));
            return List.copyOf(out);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read type relations", e);
        }
    }
}
