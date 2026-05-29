package com.anatomist.store;

import com.anatomist.model.ExtractionResult;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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
        throw new UnsupportedOperationException("not implemented");
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
