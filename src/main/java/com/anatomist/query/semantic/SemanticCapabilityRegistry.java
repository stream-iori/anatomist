package com.anatomist.query.semantic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import com.anatomist.store.SqliteStore;

/** Single source of truth for semantic operation capabilities exposed by an index. */
public final class SemanticCapabilityRegistry {
    public enum Capability {
        STREAM("semantic-stream-v1"),
        ENTITY_LOOKUP("java-entity-lookup"),
        CALL_SITES("java-call-sites"),
        TYPE_SEMANTICS("java-type-semantics"),
        DISPATCH("java-dispatch"),
        SOURCE_SNAPSHOT("source-snapshot");

        private final String id;
        Capability(String id) { this.id = id; }
        public String id() { return id; }
    }

    private final Connection connection;

    public SemanticCapabilityRegistry(Connection connection) {
        this.connection = connection;
    }

    public SemanticCapabilityRegistry(SqliteStore store) {
        try {
            this.connection = store.connection();
        } catch (SQLException failure) {
            throw new RuntimeException("failed to open semantic capability registry", failure);
        }
    }

    public boolean supports(Capability capability) {
        return switch (capability) {
            case STREAM -> true;
            case ENTITY_LOOKUP -> tableExists("nodes");
            case CALL_SITES -> tableExists("call_site_owners")
                    && tableExists("call_sites") && tableExists("call_site_targets");
            case TYPE_SEMANTICS -> tableExists("nodes") && tableExists("edges")
                    && tableExists("declarations");
            case DISPATCH -> tableExists("call_site_owners")
                    && tableExists("call_sites") && tableExists("call_site_targets")
                    && tableExists("edges") && tableExists("declarations");
            case SOURCE_SNAPSHOT -> metadata("source_root") && metadata("source_snapshot_fingerprint");
        };
    }

    public List<String> supportedIds() {
        List<String> result = new ArrayList<>();
        for (Capability capability : Capability.values()) {
            if (supports(capability)) result.add(capability.id());
        }
        return List.copyOf(result);
    }

    public void require(Capability capability) {
        if (!supports(capability)) throw new UnsupportedCapabilityException(capability.id());
    }

    private boolean tableExists(String table) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        } catch (SQLException failure) {
            throw new RuntimeException("failed to inspect semantic capability", failure);
        }
    }

    private boolean metadata(String key) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM project_meta WHERE key=? AND value IS NOT NULL AND value<>''")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        } catch (SQLException failure) {
            throw new RuntimeException("failed to inspect semantic capability", failure);
        }
    }

    public static final class UnsupportedCapabilityException extends IllegalStateException {
        private final String capability;

        public UnsupportedCapabilityException(String capability) {
            super("UNSUPPORTED_CAPABILITY: " + capability);
            this.capability = capability;
        }

        public String capability() { return capability; }
    }
}
