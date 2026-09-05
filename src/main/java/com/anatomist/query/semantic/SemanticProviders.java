package com.anatomist.query.semantic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Language/provider metadata kept separate from public operation identities. */
public final class SemanticProviders {
    public static final String JAVA = "java";

    private static final Map<String, String> PRODUCER_LANGUAGES = producerLanguages();

    private SemanticProviders() {}

    public static String languageForProducer(String producerId) {
        if (producerId == null || producerId.isBlank()) return null;
        String exact = PRODUCER_LANGUAGES.get(producerId);
        if (exact != null) return exact;
        if (producerId.startsWith("java-") || producerId.startsWith("lombok-")) {
            return JAVA;
        }
        return null;
    }

    public static List<Map<String, Object>> installedLanguages() {
        Map<String, Object> java = new LinkedHashMap<>();
        java.put("id", JAVA);
        java.put("installed", true);
        java.put("providers", List.of("java-core"));
        return List.of(java);
    }

    private static Map<String, String> producerLanguages() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("java-core", JAVA);
        out.put("java-semantics", JAVA);
        out.put("java-dispatch", JAVA);
        out.put("lombok-ast", JAVA);
        return Map.copyOf(out);
    }
}
