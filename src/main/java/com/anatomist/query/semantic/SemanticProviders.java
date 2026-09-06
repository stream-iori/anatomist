package com.anatomist.query.semantic;

import com.anatomist.provider.LanguageProvider;
import com.anatomist.provider.LanguageProviderRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Language/provider metadata kept separate from public operation identities. */
public final class SemanticProviders {
    public static final String JAVA = "java";
    private static final LanguageProviderRegistry REGISTRY = LanguageProviderRegistry.builtIns();

    private SemanticProviders() {}

    public static String languageForProducer(String producerId) {
        LanguageProvider provider = REGISTRY.providerForProducer(producerId);
        return provider == null ? null : provider.descriptor().language();
    }

    public static String providerForProducer(String producerId) {
        LanguageProvider provider = REGISTRY.providerForProducer(producerId);
        return provider == null ? null : provider.descriptor().providerId();
    }

    public static String providerForLanguage(String language) {
        LanguageProvider provider = REGISTRY.providerForLanguage(language);
        return provider == null ? null : provider.descriptor().providerId();
    }

    public static String languageForProvider(String providerId) {
        LanguageProvider provider = REGISTRY.provider(providerId);
        return provider == null ? null : provider.descriptor().language();
    }

    public static boolean installed(String language) {
        return !REGISTRY.providersForLanguage(language).isEmpty();
    }

    public static boolean installedProvider(String providerId) {
        return REGISTRY.provider(providerId) != null;
    }

    public static boolean supports(String language, String operation) {
        return REGISTRY.providersForLanguage(language).stream()
                .anyMatch(provider -> provider.descriptor().supports(operation));
    }

    public static boolean supportsProvider(String providerId, String operation) {
        LanguageProvider provider = REGISTRY.provider(providerId);
        return provider != null && provider.descriptor().supports(operation);
    }

    public static String entityKind(String producerId, String storageKind) {
        LanguageProvider provider = REGISTRY.providerForProducer(producerId);
        if (provider == null) return storageKind == null ? "entity"
                : storageKind.toLowerCase(java.util.Locale.ROOT);
        return provider.entityKind(storageKind);
    }

    public static String languageKind(String producerId, String storageKind) {
        LanguageProvider provider = REGISTRY.providerForProducer(producerId);
        return provider == null || storageKind == null ? null : provider.languageKind(storageKind);
    }

    public static List<Map<String, Object>> installedLanguages() {
        return REGISTRY.providers().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        provider -> provider.descriptor().language(),
                        LinkedHashMap::new, java.util.stream.Collectors.toList()))
                .entrySet().stream().map(entry -> {
                    Map<String, Object> language = new LinkedHashMap<>();
                    language.put("id", entry.getKey());
                    language.put("installed", true);
                    language.put("providers", entry.getValue().stream()
                            .map(provider -> provider.descriptor().providerId()).toList());
                    return language;
                }).toList();
    }
}
