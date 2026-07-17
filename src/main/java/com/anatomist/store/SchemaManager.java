package com.anatomist.store;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SchemaManager {

    private static final String SCHEMA_RESOURCE = "/schema.sql";

    private final ConnectionSupplier connSupplier;

    public SchemaManager(ConnectionSupplier connSupplier) {
        this.connSupplier = connSupplier;
    }

    public void initSchema() {
        String ddl = readSchema();
        try (Statement st = connSupplier.get().createStatement()) {
            for (String stmt : splitSqlStatements(ddl)) {
                String trimmed = stmt.trim();
                if (trimmed.isEmpty()) continue;
                st.execute(trimmed);
            }
            st.execute("PRAGMA user_version = " + IndexSchema.VERSION);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize schema", e);
        }
    }

    public boolean schemaExists() {
        try (Statement st = connSupplier.get().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='nodes'")) {
            return rs.next();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check schema existence", e);
        }
    }

    public int schemaVersion() {
        try (Statement st = connSupplier.get().createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read schema version", e);
        }
    }

    public boolean schemaCompatible() {
        return schemaVersion() == IndexSchema.VERSION;
    }

    public void clearAllData() {
        try (Statement st = connSupplier.get().createStatement()) {
            st.execute("DELETE FROM analysis_coverage");
            st.execute("DELETE FROM method_flow_coverage");
            st.execute("DELETE FROM method_flow_summaries");
            st.execute("DELETE FROM flow_edges");
            st.execute("DELETE FROM flow_nodes");
            st.execute("DELETE FROM semantic_annotations");
            st.execute("DELETE FROM annotations");
            st.execute("DELETE FROM edges");
            st.execute("DELETE FROM nodes");
            st.execute("DELETE FROM file_cache");
            st.execute("DELETE FROM file_dependencies");
            st.execute("DELETE FROM index_diagnostics");
            st.execute("DELETE FROM project_meta");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear all data", e);
        }
    }

    static String readSchema() {
        InputStream in = SchemaManager.class.getResourceAsStream(SCHEMA_RESOURCE);
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
            throw new RuntimeException(e);
        }
    }

    static List<String> splitSqlStatements(String ddl) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        String[] lines = ddl.split("\n", -1);
        for (String raw : lines) {
            String stripped = stripLineComment(raw).trim();
            current.append(raw).append('\n');
            if (stripped.isEmpty()) continue;
            String upper = stripped.toUpperCase(Locale.ROOT);
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
        if (!current.toString().trim().isEmpty()) out.add(current.toString());
        return out;
    }

    private static String stripLineComment(String line) {
        int idx = line.indexOf("--");
        return idx >= 0 ? line.substring(0, idx) : line;
    }
}
