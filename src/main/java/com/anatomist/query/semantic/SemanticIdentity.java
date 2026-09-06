package com.anatomist.query.semantic;

import com.anatomist.core.GraphSemantics;
import com.anatomist.framework.PreparedExtensions;
import com.anatomist.incremental.IndexEnvironmentFingerprint;
import com.anatomist.store.IndexRevision;
import com.anatomist.store.IndexSchema;
import com.anatomist.store.SqliteStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;

/** The three independent identities carried by semantic-stream records. */
public record SemanticIdentity(String indexRevisionId,
                               String sourceSnapshotId,
                               String semanticProfileId) {
    private static final String SOURCE_SNAPSHOT_META_KEY = "source_snapshot_fingerprint";

    public static SemanticIdentity read(Connection connection) {
        String snapshot = scalar(connection, SOURCE_SNAPSHOT_META_KEY);
        if (snapshot == null || snapshot.isBlank()) snapshot = "sha256:unknown";
        String environment = scalar(connection,
                com.anatomist.application.ProjectMetadata.SEMANTIC_PROFILE_INPUTS_KEY);
        if (environment == null || environment.isBlank()) {
            environment = scalar(connection, IndexEnvironmentFingerprint.META_KEY);
        }
        String extensions = scalar(connection, PreparedExtensions.META_KEY);
        String profile = "sha256:" + sha256(String.join("\n",
                nullToEmpty(environment), nullToEmpty(extensions),
                "schema=" + IndexSchema.VERSION,
                "graph=" + GraphSemantics.VERSION,
                "providers=" + providerProfiles(connection)));
        String revision = scalar(connection, IndexRevision.META_KEY);
        if (revision == null || revision.isBlank()) {
            // Read compatibility for an index created before revision identities existed.
            revision = "rev:legacy:" + sha256(snapshot + "\n" + profile + "\n"
                    + nullToEmpty(scalar(connection, "indexed_at"))).substring(0, 24);
        }
        return new SemanticIdentity(revision, snapshot, profile);
    }

    public static SemanticIdentity read(SqliteStore store) {
        try {
            return read(store.connection());
        } catch (SQLException failure) {
            throw new RuntimeException("failed to open index for semantic identity", failure);
        }
    }

    private static String scalar(Connection connection, String key) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM project_meta WHERE key=?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        } catch (SQLException failure) {
            throw new RuntimeException("failed to read semantic identity", failure);
        }
    }

    private static String providerProfiles(Connection connection) {
        String sql = "SELECT provider_id,profile_hash FROM index_providers ORDER BY provider_id";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            java.util.List<String> profiles = new java.util.ArrayList<>();
            while (rows.next()) profiles.add(rows.getString(1) + ":" + rows.getString(2));
            return profiles.isEmpty() ? "unknown" : String.join(",", profiles);
        } catch (SQLException failure) {
            // Only reachable for a legacy/incomplete index; compatibility checks reject it.
            return "unknown";
        }
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
