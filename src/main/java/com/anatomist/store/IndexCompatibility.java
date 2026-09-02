package com.anatomist.store;

import com.anatomist.core.GraphSemantics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Read-only compatibility and structural-integrity inspection for an index. */
public final class IndexCompatibility {

    public enum Action { CREATE, INCREMENTAL, FULL, RECREATE }

    public record Report(Action action,
                         List<String> reasons,
                         int schemaVersion,
                         int graphSemanticsVersion,
                         long documents,
                         long semanticAnnotations) {
        public boolean requiresRecreate() { return action == Action.RECREATE; }
        public String primaryReason() {
            return reasons.isEmpty() ? "UP_TO_DATE" : reasons.getFirst();
        }
    }

    private IndexCompatibility() {}

    public static Report inspect(Path database) {
        if (database == null || !Files.isRegularFile(database)) {
            return new Report(Action.CREATE, List.of("INDEX_MISSING"), 0, 0, 0, 0);
        }
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + database.toAbsolutePath())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only=ON");
            }
            String quickCheck = scalarString(connection, "PRAGMA quick_check(1)");
            if (!"ok".equalsIgnoreCase(quickCheck)) {
                return report("DATABASE_CORRUPT", 0, 0, connection);
            }
            int schema = scalarInt(connection, "PRAGMA user_version");
            if (schema != IndexSchema.VERSION) {
                return report("SCHEMA_MISMATCH", schema, 0, connection);
            }
            if (hasRow(connection, "PRAGMA foreign_key_check")) {
                return report("INDEX_INTEGRITY_FAILED", schema, 0, connection);
            }
            if (!tableExists(connection, "nodes") || !tableExists(connection, "file_cache")
                    || scalarLong(connection, "SELECT count(*) FROM nodes") == 0
                    || scalarLong(connection, "SELECT count(*) FROM file_cache") == 0) {
                return report("INDEX_EMPTY", schema, readSemantics(connection), connection);
            }
            int semantics = readSemantics(connection);
            if (semantics != GraphSemantics.VERSION) {
                return report("GRAPH_SEMANTICS_MISMATCH", schema, semantics, connection);
            }
            return new Report(Action.INCREMENTAL, List.of("UP_TO_DATE"), schema, semantics,
                    countIfPresent(connection, "documents"),
                    countIfPresent(connection, "semantic_annotations"));
        } catch (SQLException | RuntimeException failure) {
            return new Report(Action.RECREATE, List.of("DATABASE_CORRUPT"),
                    0, 0, 0, 0);
        }
    }

    private static Report report(String reason, int schema, int semantics,
                                 Connection connection) {
        return new Report(Action.RECREATE, List.of(reason), schema, semantics,
                countIfPresent(connection, "documents"),
                countIfPresent(connection, "semantic_annotations"));
    }

    private static int readSemantics(Connection connection) throws SQLException {
        if (!tableExists(connection, "project_meta")) return 0;
        try (var statement = connection.prepareStatement(
                "SELECT value FROM project_meta WHERE key=?")) {
            statement.setString(1, GraphSemantics.META_KEY);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? GraphSemantics.parse(row.getString(1)) : 0;
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private static long countIfPresent(Connection connection, String table) {
        try {
            return tableExists(connection, table)
                    ? scalarLong(connection, "SELECT count(*) FROM " + table) : 0;
        } catch (SQLException ignored) {
            return 0;
        }
    }

    private static boolean hasRow(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            return row.next();
        }
    }

    private static String scalarString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            return row.next() ? row.getString(1) : "";
        }
    }

    private static int scalarInt(Connection connection, String sql) throws SQLException {
        return (int) scalarLong(connection, sql);
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            return row.next() ? row.getLong(1) : 0;
        }
    }
}
