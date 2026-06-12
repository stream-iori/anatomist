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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class SqliteStore implements AutoCloseable {

    private static final String SCHEMA_RESOURCE = "/schema.sql";

    private final Path dbPath;
    private Connection connection;

    public SqliteStore(Path dbPath) {
        this.dbPath = dbPath;
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
            }
        }
        return connection;
    }

    public void initSchema() {
        String ddl = readSchema();
        try (Statement st = connection().createStatement()) {
            for (String stmt : splitSqlStatements(ddl)) {
                String trimmed = stmt.trim();
                if (trimmed.isEmpty()) continue;
                st.execute(trimmed);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize schema", e);
        }
    }

    public void write(ExtractionResult result) {
        if (result == null) return;
        inTransaction(c -> {
            insertNodes(c, result.nodes);
            insertEdges(c, result.edges);
            insertAnnotations(c, result.annotations);
            insertSemanticAnnotations(c, result.semanticAnnotations);
        });
    }

    /** Work to run inside a single SQLite transaction; may throw {@link SQLException}. */
    @FunctionalInterface
    public interface TxWork {
        void run(Connection c) throws SQLException;
    }

    /**
     * Runs {@code work} in a single transaction: disables autoCommit, commits on
     * success, rolls back on any {@link SQLException}, and always restores the
     * prior autoCommit flag. Centralizes the commit/rollback boilerplate that was
     * previously copy-pasted across every multi-statement write.
     */
    public void inTransaction(TxWork work) {
        Connection c;
        try {
            c = connection();
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

    private static void insertNodes(Connection c, java.util.List<Node> nodes) throws SQLException {
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

    private static void insertEdges(Connection c, java.util.List<Edge> edges) throws SQLException {
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

    private static void insertAnnotations(Connection c, java.util.List<Annotation> anns) throws SQLException {
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

    static void insertSemanticAnnotations(Connection c, java.util.List<SemanticAnnotation> sas) throws SQLException {
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

    public void upsertSemanticAnnotation(SemanticAnnotation sa) {
        if (sa == null) return;
        upsertSemanticAnnotations(java.util.List.of(sa));
    }

    public void upsertSemanticAnnotations(java.util.List<SemanticAnnotation> sas) {
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

    public void upsertArchRoles(java.util.List<com.anatomist.model.ArchRole> roles) {
        if (roles == null || roles.isEmpty()) return;
        String sql = "INSERT INTO arch_roles(node_id,role,confidence,source) VALUES (?,?,?,?)" +
                " ON CONFLICT(node_id) DO UPDATE SET role=excluded.role, confidence=excluded.confidence, source=excluded.source";
        inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (com.anatomist.model.ArchRole r : roles) {
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

    public java.util.List<com.anatomist.model.ArchRole> queryArchRoles(String role) {
        java.util.List<com.anatomist.model.ArchRole> out = new java.util.ArrayList<>();
        String sql = role == null
                ? "SELECT node_id, role, confidence, source FROM arch_roles"
                : "SELECT node_id, role, confidence, source FROM arch_roles WHERE role = ?";
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            if (role != null) ps.setString(1, role);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new com.anatomist.model.ArchRole(
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query arch_roles", e);
        }
        return out;
    }

    public java.util.Optional<com.anatomist.model.ArchRole> getArchRole(String nodeId) {
        String sql = "SELECT node_id, role, confidence, source FROM arch_roles WHERE node_id = ?";
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            ps.setString(1, nodeId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return java.util.Optional.of(new com.anatomist.model.ArchRole(
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get arch_role", e);
        }
        return java.util.Optional.empty();
    }

    public void insertDocuments(java.util.List<Document> docs) {
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

    // ===== Phase 4: incremental index support =====

    public void updateFileCache(java.util.List<FileCacheEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        Connection c;
        try {
            c = connection();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire SQLite connection", e);
        }
        String sql = "INSERT OR REPLACE INTO file_cache" +
                "(source_file,hash,schema_version,last_indexed,node_count,edge_count)" +
                " VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (FileCacheEntry e : entries) {
                ps.setString(1, e.sourceFile());
                ps.setString(2, e.hash());
                ps.setInt(3, e.schemaVersion());
                ps.setString(4, e.lastIndexed() == null ? java.time.Instant.now().toString() : e.lastIndexed());
                ps.setInt(5, e.nodeCount());
                ps.setInt(6, e.edgeCount());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update file_cache", e);
        }
    }

    public java.util.Map<String, FileCacheEntry> readFileCache() {
        Connection c;
        try {
            c = connection();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire SQLite connection", e);
        }
        java.util.Map<String, FileCacheEntry> out = new java.util.LinkedHashMap<>();
        String sql = "SELECT source_file,hash,schema_version,last_indexed,node_count,edge_count FROM file_cache";
        try (PreparedStatement ps = c.prepareStatement(sql);
             java.sql.ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                FileCacheEntry e = new FileCacheEntry(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getInt(3),
                        rs.getString(4),
                        rs.getInt(5),
                        rs.getInt(6)
                );
                out.put(e.sourceFile(), e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read file_cache", e);
        }
        return out;
    }

    public java.util.Optional<String> readProjectMeta(String key) {
        Connection c;
        try {
            c = connection();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire SQLite connection", e);
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM project_meta WHERE key=?")) {
            ps.setString(1, key);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return java.util.Optional.ofNullable(rs.getString(1));
                return java.util.Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read project_meta", e);
        }
    }

    public void upsertProjectMeta(String key, String value) {
        Connection c;
        try {
            c = connection();
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

    public void deriveFileDependencies() {
        Connection c;
        try {
            c = connection();
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

    public void clearFileDependencies() {
        Connection c;
        try {
            c = connection();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire SQLite connection", e);
        }
        try (Statement st = c.createStatement()) {
            st.execute("DELETE FROM file_dependencies");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear file_dependencies", e);
        }
    }

    public void deleteBySourceFiles(java.util.List<String> sourceFiles) {
        if (sourceFiles == null || sourceFiles.isEmpty()) return;
        Connection c;
        try {
            c = connection();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire SQLite connection", e);
        }
        // semantic_annotations.node_id is ON DELETE SET NULL — clean explicitly first.
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

    /** Files that directly depend on any of {@code seed} (single-level reverse lookup). */
    public java.util.Set<String> dependentsOf(java.util.List<String> seed) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (seed == null || seed.isEmpty()) return out;
        Connection c;
        try {
            c = connection();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire SQLite connection", e);
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT source_file FROM file_dependencies WHERE depends_on_file = ?")) {
            for (String f : seed) {
                ps.setString(1, f);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query file_dependencies", e);
        }
        return out;
    }

    /** All node ids currently present in the DB (reflects prior deletes in the same tx). */
    public java.util.Set<String> allNodeIds() {        java.util.Set<String> out = new java.util.HashSet<>();
        Connection c;
        try {
            c = connection();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire SQLite connection", e);
        }
        try (Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT id FROM nodes")) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read node ids", e);
        }
        return out;
    }

    /**
     * Remove the entire Spring-bean subgraph: all BEAN nodes (their DEFINED_BY
     * edges cascade via FK) plus WIRES edges, which originate from CLASS nodes and
     * therefore do <em>not</em> cascade when only the bean nodes go away. Used by
     * the incremental path to rebuild the bean graph wholesale whenever any
     * {@code <beans>} XML in the reparse closure changes.
     */
    public void deleteSpringBeanGraph() {
        Connection c;
        try {
            c = connection();
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

    private static String readSchema() {
        InputStream in = SqliteStore.class.getResourceAsStream(SCHEMA_RESOURCE);
        if (in == null) {
            throw new IllegalStateException("Missing classpath resource: " + SCHEMA_RESOURCE);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            throw new UncheckedIO(e);
        }
    }

    /**
     * Splits a DDL script into individual statements. Honors `BEGIN ... END;`
     * blocks (used by FTS5 triggers) — semicolons inside such a block are not
     * statement boundaries.
     */
    static java.util.List<String> splitSqlStatements(String ddl) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        String[] lines = ddl.split("\n", -1);
        for (String raw : lines) {
            String line = raw;
            String stripped = stripLineComment(line).trim();
            current.append(raw).append('\n');
            if (stripped.isEmpty()) continue;
            String upper = stripped.toUpperCase(java.util.Locale.ROOT);
            if (upper.endsWith("BEGIN") || upper.contains(" BEGIN") || upper.equals("BEGIN")) {
                depth++;
            }
            if (upper.startsWith("END;") || upper.equals("END")) {
                depth--;
                if (depth < 0) depth = 0;
                if (depth == 0) {
                    out.add(current.toString());
                    current.setLength(0);
                    continue;
                }
            }
            if (depth == 0 && stripped.endsWith(";")) {
                out.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.toString().trim().length() > 0) out.add(current.toString());
        return out;
    }

    private static String stripLineComment(String line) {
        int idx = line.indexOf("--");
        return idx >= 0 ? line.substring(0, idx) : line;
    }

    private static final class UncheckedIO extends RuntimeException {
        UncheckedIO(IOException cause) { super(cause); }
    }
}
