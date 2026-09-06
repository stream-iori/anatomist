package com.anatomist.provider;

import com.anatomist.core.NodeKeyFactory;
import com.anatomist.core.ProjectScanner;
import com.anatomist.core.SourceIdentity;
import com.anatomist.core.SourceRoot;
import com.anatomist.core.SourceScope;
import com.anatomist.provider.java.JavaLanguageProvider;
import com.anatomist.store.SqliteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LanguageProviderContractTest {
    @Test
    void javaPythonRustAndTypeScriptShareOneProviderContract(@TempDir Path project)
            throws Exception {
        List<LanguageProvider> providers = List.of(
                new JavaLanguageProvider(),
                stub("python3-ast", "PYTHON", "py"),
                stub("rust-analyzer", "rust", ".rs"),
                stub("typescript-compiler", "typescript", ".ts"));
        LanguageProviderRegistry registry = new LanguageProviderRegistry(providers);

        Files.writeString(project.resolve("Case.java"), "class Case {}");
        Files.writeString(project.resolve("case.py"), "class Case: pass");
        Files.writeString(project.resolve("case.rs"), "struct Case;");
        Files.writeString(project.resolve("case.ts"), "class Case {}");
        SourceRoot root = new SourceRoot(project, "app", SourceScope.MAIN);

        for (LanguageProvider provider : registry.providers()) {
            SourceInventory inventory = provider.discover(new ProjectScanner(), List.of(root));
            assertEquals(1, inventory.files().size());
            assertEquals(provider.descriptor().fileExtensions().iterator().next(),
                    extension(inventory.files().getFirst()));
            assertEquals("type", provider.entityKind("CLASS"));
        }

        SourceIdentity identity = new SourceIdentity("app", SourceScope.MAIN);
        String java = NodeKeyFactory.key("java-core", identity, "example.Case");
        String python = NodeKeyFactory.key("python3-ast", identity, "example.Case");
        assertNotEquals(java, python);
        assertEquals("java-core", NodeKeyFactory.providerId(java));
        assertEquals("python3-ast", NodeKeyFactory.providerId(python));
    }

    @Test
    void providerRegistryRejectsDuplicateIds() {
        assertThrows(IllegalArgumentException.class, () -> new LanguageProviderRegistry(
                List.of(stub("same", "python", ".py"), stub("same", "rust", ".rs"))));
    }

    @Test
    void indexRecordsExactProviderProfiles(@TempDir Path project) throws Exception {
        List<LanguageProvider> providers = List.of(
                new JavaLanguageProvider(), stub("python3-ast", "python", ".py"),
                stub("rust-analyzer", "rust", ".rs"),
                stub("typescript-compiler", "typescript", ".ts"));
        try (SqliteStore store = new SqliteStore(project.resolve("index.db"))) {
            store.initSchema();
            ProviderIndexMetadata.replace(store, providers);
            try (var statement = store.connection().prepareStatement(
                    "SELECT provider_id,language,operations,profile_hash "
                            + "FROM index_providers ORDER BY provider_id");
                 var rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    count++;
                    org.junit.jupiter.api.Assertions.assertTrue(
                            rows.getString("operations").contains("\"search\""));
                    org.junit.jupiter.api.Assertions.assertTrue(
                            rows.getString("profile_hash").startsWith("sha256:"));
                }
                assertEquals(4, count);
            }
        }
    }

    private static LanguageProvider stub(String id, String language, String extension) {
        ProviderDescriptor descriptor = new ProviderDescriptor(id, language, "contract-test",
                Set.of(extension), Set.of("search"), Map.of());
        return new LanguageProvider() {
            @Override public ProviderDescriptor descriptor() { return descriptor; }
            @Override public SymbolSelectorCodec selectors() { return (selector, kind) -> selector; }
            @Override public ProviderSemanticAdapter semantics() { return () -> Set.of(); }
            @Override public String entityKind(String languageKind) {
                return "CLASS".equals(languageKind) ? "type" : "entity";
            }
        };
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        return name.substring(name.lastIndexOf('.'));
    }
}
