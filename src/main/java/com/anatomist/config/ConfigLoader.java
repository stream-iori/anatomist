package com.anatomist.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ConfigLoader {

    private static final String CONFIG_FILE = "config.toml";
    private static final String DOT_DIR = ".anatomist";

    public static ProjectConfig load(Path projectRoot) {
        ProjectConfig config = new ProjectConfig();

        Path envPath = envConfigPath();
        if (envPath != null && Files.isRegularFile(envPath)) {
            applyToml(config, envPath);
        }

        Path userWide = userWidePath();
        if (userWide != null && Files.isRegularFile(userWide)) {
            applyToml(config, userWide);
        }

        if (projectRoot != null) {
            Path projectLocal = projectRoot.resolve(DOT_DIR).resolve(CONFIG_FILE);
            if (Files.isRegularFile(projectLocal)) {
                applyToml(config, projectLocal);
            }
        }

        return config;
    }

    private static Path envConfigPath() {
        String val = System.getenv("ANATOMIST_CONFIG");
        return val != null && !val.isBlank() ? Path.of(val) : null;
    }

    private static Path userWidePath() {
        String home = System.getProperty("user.home");
        if (home == null) return null;
        return Path.of(home, DOT_DIR, CONFIG_FILE);
    }

    static void applyToml(ProjectConfig config, Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            return;
        }
        applyToml(config, lines);
    }

    static void applyToml(ProjectConfig config, List<String> lines) {
        String section = "";
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;

            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).strip();
                continue;
            }

            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();

            applyKeyValue(config, section, key, value);
        }
    }

    private static void applyKeyValue(ProjectConfig config, String section, String key, String value) {
        switch (section) {
            case "index" -> applyIndex(config, key, value);
            case "external" -> applyExternal(config, key, value);
            default -> {}
        }
    }

    private static void applyIndex(ProjectConfig config, String key, String value) {
        switch (key) {
            case "java_version" -> config.setJavaVersion(parseInt(value, 8));
            case "include_tests" -> config.setIncludeTests(parseBool(value));
            case "spring_xml" -> config.setSpringXml(parseBool(value));
            case "vm_classpath" -> config.setVmClasspath(parseBool(value));
            case "exclude" -> config.setExclude(parseStringArray(value));
            default -> {}
        }
    }

    private static void applyExternal(ProjectConfig config, String key, String value) {
        switch (key) {
            case "exclude_patterns" -> config.setExternalExcludePatterns(parseStringArray(value));
            default -> {}
        }
    }

    static List<String> parseStringArray(String value) {
        if (!value.startsWith("[") || !value.endsWith("]")) {
            return List.of(unquote(value));
        }
        String inner = value.substring(1, value.length() - 1).strip();
        if (inner.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (String part : splitComma(inner)) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                result.add(unquote(trimmed));
            }
        }
        return result;
    }

    private static List<String> splitComma(String s) {
        List<String> parts = new ArrayList<>();
        boolean inQuote = false;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inQuote = !inQuote;
            else if (c == ',' && !inQuote) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static int parseInt(String value, int defaultVal) {
        try { return Integer.parseInt(value.strip()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private static boolean parseBool(String value) {
        String v = value.strip().toLowerCase(Locale.ROOT);
        return "true".equals(v) || "1".equals(v) || "yes".equals(v);
    }
}
