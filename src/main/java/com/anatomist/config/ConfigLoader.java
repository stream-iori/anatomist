package com.anatomist.config;

import com.anatomist.core.SourceScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Selects exactly one config.toml and parses the supported strict TOML subset. */
public final class ConfigLoader {
    public static final String CONFIG_FILE = "config.toml";
    public static final String DOT_DIR = ".anatomist";

    private ConfigLoader() {}

    public static ProjectConfig load(Path projectRoot) {
        return loadResolved(projectRoot).config();
    }

    public static LoadedConfig loadResolved(Path projectRoot) {
        String home = System.getProperty("user.home");
        return loadResolved(projectRoot, home == null ? null : Path.of(home));
    }

    static LoadedConfig loadResolved(Path projectRoot, Path userHome) {
        Path projectFile = projectRoot == null ? null
                : projectRoot.resolve(DOT_DIR).resolve(CONFIG_FILE);
        if (projectFile != null && Files.exists(projectFile)) {
            return loadFile(projectFile, LoadedConfig.Source.PROJECT);
        }
        Path userFile = userHome == null ? null : userHome.resolve(DOT_DIR).resolve(CONFIG_FILE);
        if (userFile != null && Files.exists(userFile)) {
            return loadFile(userFile, LoadedConfig.Source.USER);
        }
        return new LoadedConfig(new ProjectConfig(), LoadedConfig.Source.DEFAULT, null);
    }

    private static LoadedConfig loadFile(Path file, LoadedConfig.Source source) {
        if (!Files.isRegularFile(file)) {
            throw new ConfigException(file, 0, "configuration path is not a regular file");
        }
        ProjectConfig config = new ProjectConfig();
        applyToml(config, file);
        validate(config, file);
        return new LoadedConfig(config, source, file.toAbsolutePath().normalize());
    }

    static void applyToml(ProjectConfig config, Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new ConfigException(file, "unable to read configuration: " + e.getMessage(), e);
        }
        applyToml(config, lines, file);
    }

    static void applyToml(ProjectConfig config, List<String> lines) {
        applyToml(config, lines, null);
    }

    private static void applyToml(ProjectConfig config, List<String> lines, Path file) {
        String section = "";
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < lines.size(); index++) {
            int lineNumber = index + 1;
            String line = lines.get(index).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;

            if (line.startsWith("[")) {
                if (!(line.startsWith("[") && line.endsWith("]"))) {
                    throw error(file, lineNumber, "malformed section header");
                }
                section = line.substring(1, line.length() - 1).strip();
                if (!Set.of("index", "scan", "external", "extensions.lombok").contains(section)) {
                    throw error(file, lineNumber, "unknown section [" + section + "]");
                }
                continue;
            }

            int eq = line.indexOf('=');
            if (eq <= 0) throw error(file, lineNumber, "expected key = value");
            if (section.isEmpty()) throw error(file, lineNumber, "key must be inside a section");
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (key.isEmpty() || value.isEmpty()) throw error(file, lineNumber, "expected key = value");
            String qualified = section + "." + key;
            if (!seen.add(qualified)) throw error(file, lineNumber, "duplicate key " + qualified);
            applyKeyValue(config, section, key, value, file, lineNumber);
        }
    }

    private static void applyKeyValue(ProjectConfig config, String section, String key,
                                      String value, Path file, int line) {
        switch (section) {
            case "index" -> applyIndex(config, key, value, file, line);
            case "scan" -> applyScan(config, key, value, file, line);
            case "external" -> {
                if (!"exclude_patterns".equals(key)) throw unknown(file, line, section, key);
                config.setExternalExcludePatterns(parseStringArray(value, file, line));
            }
            case "extensions.lombok" -> applyLombok(config, key, value, file, line);
            default -> throw unknown(file, line, section, key);
        }
    }

    private static void applyLombok(ProjectConfig config, String key, String value,
                                    Path file, int line) {
        switch (key) {
            case "mode" -> {
                String mode = parseString(value, file, line);
                try {
                    config.setLombokMode(mode);
                } catch (IllegalArgumentException failure) {
                    throw error(file, line, failure.getMessage());
                }
            }
            case "strict" -> config.setLombokStrict(parseBool(value, file, line));
            default -> throw unknown(file, line, "extensions.lombok", key);
        }
    }

    private static void applyIndex(ProjectConfig config, String key, String value,
                                   Path file, int line) {
        switch (key) {
            case "java_version" -> config.setJavaVersion(parseInt(value, file, line));
            case "spring_xml" -> config.setSpringXml(parseBool(value, file, line));
            case "vm_classpath" -> config.setVmClasspath(parseBool(value, file, line));
            case "dataflow", "dataflow_mode", "dataflow_scopes", "implicit_taint" ->
                    throw error(file, line, "removed key index." + key
                            + "; dataflow was removed; use call-path and context --source instead");
            case "include_tests", "exclude" -> throw error(file, line,
                    "removed key index." + key + "; use [scan] instead");
            default -> throw unknown(file, line, "index", key);
        }
    }

    private static void applyScan(ProjectConfig config, String key, String value,
                                  Path file, int line) {
        switch (key) {
            case "scopes" -> {
                List<SourceScope> scopes = new ArrayList<>();
                for (String raw : parseStringArray(value, file, line)) {
                    try {
                        scopes.add(SourceScope.valueOf(raw.toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException ex) {
                        throw error(file, line, "scan scope must be MAIN, TEST, or GENERATED: " + raw);
                    }
                }
                config.setScanScopes(scopes);
            }
            case "include" -> config.setScanIncludes(parseStringArray(value, file, line));
            case "exclude" -> config.setScanExcludes(parseStringArray(value, file, line));
            case "source_roots" -> config.setSourceRootSpecs(parseStringArray(value, file, line));
            default -> throw unknown(file, line, "scan", key);
        }
    }

    static List<String> parseStringArray(String value) {
        return parseStringArray(value, null, 0);
    }

    private static List<String> parseStringArray(String value, Path file, int line) {
        if (!value.startsWith("[") || !value.endsWith("]")) {
            throw error(file, line, "expected an array of quoted strings");
        }
        String inner = value.substring(1, value.length() - 1).strip();
        if (inner.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (String part : splitComma(inner, file, line)) {
            result.add(parseString(part.strip(), file, line));
        }
        return List.copyOf(result);
    }

    private static List<String> splitComma(String value, Path file, int line) {
        List<String> parts = new ArrayList<>();
        boolean quoted = false;
        boolean escaped = false;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) escaped = false;
            else if (c == '\\' && quoted) escaped = true;
            else if (c == '"') quoted = !quoted;
            else if (c == ',' && !quoted) {
                parts.add(value.substring(start, i));
                start = i + 1;
            }
        }
        if (quoted) throw error(file, line, "unterminated string");
        parts.add(value.substring(start));
        if (parts.stream().anyMatch(String::isBlank)) throw error(file, line, "empty array element");
        return parts;
    }

    static String unquote(String value) {
        return parseString(value, null, 0);
    }

    private static String parseString(String value, Path file, int line) {
        if (value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"")) {
            throw error(file, line, "expected a quoted string");
        }
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i >= value.length() - 1) throw error(file, line, "unterminated escape");
            char escaped = value.charAt(i);
            switch (escaped) {
                case '"', '\\' -> out.append(escaped);
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                default -> throw error(file, line, "unsupported escape \\" + escaped);
            }
        }
        return out.toString();
    }

    private static int parseInt(String value, Path file, int line) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw error(file, line, "expected an integer: " + value);
        }
    }

    private static boolean parseBool(String value, Path file, int line) {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw error(file, line, "expected true or false: " + value);
    }

    private static void validate(ProjectConfig config, Path file) {
        if (!config.sourceRootSpecs().isEmpty() && config.scanScopesConfigured()) {
            throw error(file, 0, "scan.source_roots and scan.scopes are mutually exclusive");
        }
        validatePatterns(config.scanIncludes(), "scan.include", file);
        validatePatterns(config.scanExcludes(), "scan.exclude", file);
    }

    private static void validatePatterns(List<String> patterns, String key, Path file) {
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) throw error(file, 0, key + " contains a blank pattern");
            String normalized = pattern.replace('\\', '/');
            if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")) {
                throw error(file, 0, key + " patterns must be project-relative: " + pattern);
            }
            for (String part : normalized.split("/")) {
                if ("..".equals(part)) throw error(file, 0, key + " patterns cannot contain '..': " + pattern);
            }
            if (normalized.indexOf('!') >= 0 || normalized.indexOf('{') >= 0
                    || normalized.indexOf('}') >= 0 || normalized.indexOf('[') >= 0
                    || normalized.indexOf(']') >= 0) {
                throw error(file, 0, key + " supports only *, **, and ?: " + pattern);
            }
        }
    }

    private static ConfigException unknown(Path file, int line, String section, String key) {
        return error(file, line, "unknown key " + section + "." + key);
    }

    private static ConfigException error(Path file, int line, String message) {
        return new ConfigException(file, line, message);
    }
}
