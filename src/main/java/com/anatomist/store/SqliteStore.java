package com.anatomist.store;

import com.anatomist.model.Annotation;
import com.anatomist.model.Document;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.model.Node;
import com.anatomist.model.SemanticAnnotation;

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
            }
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
            DataWriter.insertSemanticAnnotations(c, result.semanticAnnotations);
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
    public void updateFileCache(List<FileCacheEntry> entries) { writer.updateFileCache(entries); }
    public void upsertProjectMeta(String key, String value) { writer.upsertProjectMeta(key, value); }
    public void deleteBySourceFiles(List<String> sourceFiles) { writer.deleteBySourceFiles(sourceFiles); }
    public void deleteSpringBeanGraph() { writer.deleteSpringBeanGraph(); }
    public void replaceGeneratedWiringEdges(List<Edge> edges) { writer.replaceGeneratedWiringEdges(edges); }
    public void replaceGeneratedWiringEdgesInCurrentTransaction(List<Edge> edges) {
        writer.replaceGeneratedWiringEdgesInCurrentTransaction(edges);
    }
    public void clearFileDependencies() { writer.clearFileDependencies(); }
    public void deriveFileDependencies() { writer.deriveFileDependencies(); }
    public void refreshFileDependencies() { writer.refreshFileDependencies(); }

    @FunctionalInterface
    public interface TxWork {
        void run(Connection c) throws java.sql.SQLException;
    }

    public void inTransaction(TxWork work) { writer.inTransaction(work::run); }

    // ── Read ────────────────────────────────────────────────────────────

    public Map<String, FileCacheEntry> readFileCache() { return reader.readFileCache(); }
    public Optional<String> readProjectMeta(String key) { return reader.readProjectMeta(key); }
    public Set<String> dependentsOf(List<String> seed) { return reader.dependentsOf(seed); }
    public Set<String> allNodeIds() { return reader.allNodeIds(); }
    public Map<String, FileCacheService.SourceFileStats> sourceFileStats() { return reader.sourceFileStats(); }
    public FileCacheService.SourceFileStats countRowsDeletedBySourceFiles(List<String> sourceFiles) {
        return reader.countRowsDeletedBySourceFiles(sourceFiles);
    }
    public FileCacheService.SourceFileStats countSpringBeanGraphRows() { return reader.countSpringBeanGraphRows(); }
    public int countGeneratedWiringEdges() { return reader.countGeneratedWiringEdges(); }
    public List<Edge> readWiringSourceEdges() { return reader.readWiringSourceEdges(); }
    public Map<String, Long> queryKindCounts() { return reader.queryKindCounts(); }
    public Map<String, Long> queryRelationCounts() { return reader.queryRelationCounts(); }
    public long queryAnnotationCount() { return reader.queryAnnotationCount(); }
    public long querySemanticAnnotationCount() { return reader.querySemanticAnnotationCount(); }
}
