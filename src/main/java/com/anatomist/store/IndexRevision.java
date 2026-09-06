package com.anatomist.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Identity of one committed, query-visible fact set. */
public final class IndexRevision {
    public static final String META_KEY = "index_revision_id";

    private IndexRevision() {}

    public static String next() {
        return "rev:" + UUID.randomUUID();
    }

    public static String bump(Connection connection) throws SQLException {
        String revision = next();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO project_meta(key,value) VALUES (?,?)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value
                """)) {
            statement.setString(1, META_KEY);
            statement.setString(2, revision);
            statement.executeUpdate();
        }
        return revision;
    }

    public static String read(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM project_meta WHERE key=?")) {
            statement.setString(1, META_KEY);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }
}
