package com.anatomist.provider;

import com.anatomist.json.Json;
import com.anatomist.store.FileCacheService;
import com.anatomist.store.SqliteStore;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Persists the exact provider set that produced an index snapshot. */
public final class ProviderIndexMetadata {
    private ProviderIndexMetadata() {}

    public static void replaceBuiltIns(SqliteStore store) {
        replace(store, LanguageProviderRegistry.builtIns().providers());
    }

    public static void replaceProvider(SqliteStore store, String providerId) {
        replace(store, List.of(LanguageProviderRegistry.builtIns().requireProvider(providerId)));
    }

    public static void replace(SqliteStore store, List<? extends LanguageProvider> providers) {
        List<LanguageProvider> ordered = providers == null ? List.of() : providers.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(provider -> provider.descriptor().providerId()))
                .map(provider -> (LanguageProvider) provider)
                .toList();
        store.inTransaction(connection -> {
            try (java.sql.Statement clear = connection.createStatement()) {
                clear.executeUpdate("DELETE FROM index_providers");
            }
            String sql = "INSERT INTO index_providers(provider_id,language,provider_version,"
                    + "operations,limitations,profile_hash) VALUES(?,?,?,?,?,?)";
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                for (LanguageProvider provider : ordered) {
                    ProviderDescriptor descriptor = provider.descriptor();
                    List<String> operations = descriptor.operations().stream().sorted().toList();
                    String operationJson = Json.writeCompact(operations);
                    java.util.Map<String, List<String>> limitations = new java.util.TreeMap<>();
                    descriptor.limitations().forEach((operation, values) ->
                            limitations.put(operation, values.stream().sorted().toList()));
                    String limitationJson = Json.writeCompact(limitations);
                    String extensionsJson = Json.writeCompact(
                            descriptor.fileExtensions().stream().sorted().toList());
                    List<String> canonical = new ArrayList<>();
                    canonical.add(descriptor.providerId()); canonical.add(descriptor.language());
                    canonical.add(descriptor.version()); canonical.add(extensionsJson);
                    canonical.add(operationJson);
                    canonical.add(limitationJson);
                    String hash = "sha256:" + FileCacheService.sha256OfString(
                            String.join("\n", canonical));
                    insert.setString(1, descriptor.providerId());
                    insert.setString(2, descriptor.language());
                    insert.setString(3, descriptor.version());
                    insert.setString(4, operationJson);
                    insert.setString(5, limitationJson);
                    insert.setString(6, hash);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        });
    }
}
