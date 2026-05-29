package com.anatomist.store;

import com.anatomist.model.Annotation;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;

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
            insertNodes(c, result.nodes);
            insertEdges(c, result.edges);
            insertAnnotations(c, result.annotations);
            c.commit();
        } catch (SQLException e) {
            try { c.rollback(); } catch (SQLException ignore) {}
            throw new RuntimeException("Failed to write extraction result", e);
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
