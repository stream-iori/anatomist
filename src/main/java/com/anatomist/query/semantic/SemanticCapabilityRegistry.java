package com.anatomist.query.semantic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import com.anatomist.store.SqliteStore;

/** Read-only availability probes keyed by public, language-neutral operation IDs. */
public final class SemanticCapabilityRegistry {
    private static final List<String> OPERATIONS = List.of(
            "search", "resolve", "describe", "members", "type-relations",
            "runtime-implementations", "callable-relations", "calls", "dispatch",
            "bindings", "annotations", "related-docs", "references", "accesses",
            "regions", "sites-in", "trace", "source", "declarations-of", "overview");

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

    public boolean supports(String operation) {
        return switch (operation) {
            case "search", "resolve", "describe" -> tableExists("nodes");
            case "declarations-of" -> tables("nodes", "declarations");
            case "calls" -> tables("nodes", "call_site_owners", "call_sites",
                    "call_site_targets");
            case "dispatch" -> tables("nodes", "edges", "declarations",
                    "call_site_owners", "call_sites", "call_site_targets");
            case "annotations" -> tables("nodes", "annotations");
            case "related-docs" -> tables("nodes", "documents", "semantic_annotations");
            case "source" -> tableExists("nodes") && metadata("source_root")
                    && metadata("source_snapshot_fingerprint");
            case "members", "type-relations", "runtime-implementations",
                 "callable-relations", "bindings", "references", "accesses",
                 "regions", "sites-in", "trace", "overview" -> tables("nodes", "edges");
            default -> false;
        };
    }

    public boolean supports(String operation, String language, String providerId) {
        String provider = providerId == null || providerId.isBlank()
                ? SemanticProviders.providerForLanguage(language) : providerId;
        if (provider == null || !SemanticProviders.supportsProvider(provider, operation)
                || !java.util.Objects.equals(language,
                        SemanticProviders.languageForProvider(provider))
                || !indexedProviderSupports(provider, language, operation)) return false;
        return supports(operation);
    }

    public List<String> supportedIds() {
        return OPERATIONS.stream().filter(this::supports).toList();
    }

    public void require(String operation) {
        if (!supports(operation)) throw new UnsupportedCapabilityException(operation, "java");
    }

    public void require(String operation, String language, String providerId) {
        if (!supports(operation, language, providerId)) {
            throw new UnsupportedCapabilityException(operation, language);
        }
    }

    private boolean indexedProviderSupports(String provider, String language, String operation) {
        if (!tableExists("index_providers")) return false;
        String sql = "SELECT operations FROM index_providers WHERE provider_id=? AND language=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, provider);
            statement.setString(2, language);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return false;
                Object parsed = com.anatomist.json.Json.parseTree(rows.getString(1));
                if (!(parsed instanceof List<?> values)) return false;
                return values.stream().anyMatch(operation::equals);
            }
        } catch (SQLException | RuntimeException failure) {
            return false;
        }
    }

    private boolean tables(String... names) {
        for (String name : names) if (!tableExists(name)) return false;
        return true;
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
        private final String operation;
        private final String language;

        public UnsupportedCapabilityException(String operation, String language) {
            super("UNSUPPORTED_CAPABILITY: operation " + operation + " is unavailable for "
                    + language);
            this.operation = operation;
            this.language = language;
        }

        public String operation() { return operation; }
        public String capability() { return operation; }
        public String language() { return language; }
    }
}
