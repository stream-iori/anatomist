package com.anatomist.framework.lombok;

import com.anatomist.store.FileCacheService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deliberately small, signature-focused reader for the project-root lombok.config. */
public record LombokConfiguration(
        boolean noIsPrefix,
        String logFieldName,
        boolean logFieldStatic,
        boolean partial,
        String fingerprint
) {
    public static LombokConfiguration load(Path projectRoot) {
        Path file = projectRoot == null ? null : projectRoot.resolve("lombok.config");
        if (file == null || !Files.isRegularFile(file)) {
            return new LombokConfiguration(false, "log", true, false,
                    FileCacheService.sha256OfString("lombok-config:none"));
        }
        try {
            String content = Files.readString(file);
            Map<String, String> values = new LinkedHashMap<>();
            boolean partial = false;
            for (String raw : content.lines().toList()) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int equals = line.indexOf('=');
                if (equals <= 0) { partial = true; continue; }
                String key = line.substring(0, equals).strip();
                String value = line.substring(equals + 1).strip();
                if (switch (key) {
                    case "lombok.getter.noIsPrefix", "lombok.log.fieldName",
                         "lombok.log.fieldIsStatic" -> true;
                    default -> false;
                }) values.put(key, value);
                else partial = true;
            }
            boolean noIsPrefix = bool(values.get("lombok.getter.noIsPrefix"), false);
            boolean logStatic = bool(values.get("lombok.log.fieldIsStatic"), true);
            if (!validBoolean(values.get("lombok.getter.noIsPrefix"))
                    || !validBoolean(values.get("lombok.log.fieldIsStatic"))) {
                partial = true;
            }
            String fieldName = values.getOrDefault("lombok.log.fieldName", "log");
            if (!fieldName.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                fieldName = "log";
                partial = true;
            }
            return new LombokConfiguration(noIsPrefix, fieldName, logStatic, partial,
                    FileCacheService.sha256OfString(content));
        } catch (IOException failure) {
            return new LombokConfiguration(false, "log", true, true,
                    FileCacheService.sha256OfString("lombok-config:unreadable"));
        }
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
