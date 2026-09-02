package com.anatomist.framework.lombok;

import com.anatomist.store.FileCacheService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Deliberately small, signature-focused reader for the project-root lombok.config. */
public record LombokConfiguration(
        boolean noIsPrefix,
        String logFieldName,
        boolean logFieldStatic,
        Set<String> uncertainCapabilities,
        String fingerprint
) {
    public LombokConfiguration {
        uncertainCapabilities = uncertainCapabilities == null
                ? Set.of() : Set.copyOf(uncertainCapabilities);
    }

    public boolean partial() {
        return !uncertainCapabilities.isEmpty();
    }

    public static LombokConfiguration load(Path projectRoot) {
        Path file = projectRoot == null ? null : projectRoot.resolve("lombok.config");
        if (file == null || !Files.isRegularFile(file)) {
            return new LombokConfiguration(false, "log", true, Set.of(),
                    FileCacheService.sha256OfString("lombok-config:none"));
        }
        try {
            String content = Files.readString(file);
            Map<String, String> values = new LinkedHashMap<>();
            Set<String> uncertain = new TreeSet<>();
            for (String raw : content.lines().toList()) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int equals = line.indexOf('=');
                if (equals <= 0) {
                    uncertain.add(LombokCapabilitySummary.WILDCARD);
                    continue;
                }
                String key = line.substring(0, equals).strip();
                String value = line.substring(equals + 1).strip();
                if (switch (key) {
                    case "lombok.getter.noIsPrefix", "lombok.log.fieldName",
                         "lombok.log.fieldIsStatic" -> true;
                    default -> false;
                }) values.put(key, value);
                else uncertain.addAll(affectedCapabilities(key));
            }
            boolean noIsPrefix = bool(values.get("lombok.getter.noIsPrefix"), false);
            boolean logStatic = bool(values.get("lombok.log.fieldIsStatic"), true);
            if (!validBoolean(values.get("lombok.getter.noIsPrefix"))) {
                uncertain.add(LombokCapabilitySummary.GETTER);
            }
            if (!validBoolean(values.get("lombok.log.fieldIsStatic"))) {
                uncertain.add(LombokCapabilitySummary.LOGGER_FIELD);
            }
            String fieldName = values.getOrDefault("lombok.log.fieldName", "log");
            if (!fieldName.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                fieldName = "log";
                uncertain.add(LombokCapabilitySummary.LOGGER_FIELD);
            }
            return new LombokConfiguration(noIsPrefix, fieldName, logStatic, uncertain,
                    FileCacheService.sha256OfString(content));
        } catch (IOException failure) {
            return new LombokConfiguration(false, "log", true,
                    Set.of(LombokCapabilitySummary.WILDCARD),
                    FileCacheService.sha256OfString("lombok-config:unreadable"));
        }
    }

    private static Set<String> affectedCapabilities(String key) {
        if (key.startsWith("lombok.getter.")) return Set.of(LombokCapabilitySummary.GETTER);
        if (key.startsWith("lombok.setter.")) return Set.of(LombokCapabilitySummary.SETTER);
        if (key.startsWith("lombok.accessors.")) {
            return Set.of(LombokCapabilitySummary.GETTER, LombokCapabilitySummary.SETTER,
                    "accessors");
        }
        if (key.startsWith("lombok.noArgsConstructor.")) {
            return Set.of(LombokCapabilitySummary.NO_ARGS_CONSTRUCTOR);
        }
        if (key.startsWith("lombok.requiredArgsConstructor.")) {
            return Set.of(LombokCapabilitySummary.REQUIRED_CONSTRUCTOR);
        }
        if (key.startsWith("lombok.allArgsConstructor.")) {
            return Set.of(LombokCapabilitySummary.ALL_ARGS_CONSTRUCTOR);
        }
        if (key.startsWith("lombok.anyConstructor.")) {
            return Set.of(LombokCapabilitySummary.NO_ARGS_CONSTRUCTOR,
                    LombokCapabilitySummary.REQUIRED_CONSTRUCTOR,
                    LombokCapabilitySummary.ALL_ARGS_CONSTRUCTOR);
        }
        if (key.startsWith("lombok.log.")) return Set.of(LombokCapabilitySummary.LOGGER_FIELD);
        if (key.startsWith("lombok.equalsAndHashCode.")) {
            return Set.of(LombokCapabilitySummary.EQUALS_HASH_CODE);
        }
        if (key.startsWith("lombok.toString.")) return Set.of(LombokCapabilitySummary.TO_STRING);
        if (key.startsWith("lombok.builder.")) return Set.of("builder");
        return Set.of(LombokCapabilitySummary.WILDCARD);
    }

    private static boolean bool(String value, boolean fallback) {
        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        return fallback;
    }

    private static boolean validBoolean(String value) {
        return value == null || "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
    }
}
