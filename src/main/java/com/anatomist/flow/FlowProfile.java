package com.anatomist.flow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Data-flow materialization profile and bounded selector matching. */
public record FlowProfile(Mode mode, List<String> scopes) {

    public enum Mode {
        OFF, FULL, SUMMARY, SCOPED;

        public static Mode parse(String value) {
            if (value == null || value.isBlank()) return null;
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "dataflow mode must be off, full, summary, or scoped: " + value);
            }
        }
    }

    public FlowProfile {
        mode = mode == null ? Mode.OFF : mode;
        List<String> normalized = new ArrayList<>();
        if (scopes != null) {
            for (String scope : scopes) {
                if (scope == null || scope.isBlank()) continue;
                String value = scope.trim();
                int separator = value.indexOf(':');
                String kind = separator < 0 ? "" : value.substring(0, separator);
                String pattern = separator < 0 ? "" : value.substring(separator + 1);
                if (!List.of("package", "method", "source").contains(kind)
                        || pattern.isBlank()) {
                    throw new IllegalArgumentException(
                            "dataflow scope must be package:<glob>, method:<glob>, or source:<glob>: "
                                    + value);
                }
                normalized.add(kind + ":" + pattern);
            }
        }
        normalized.sort(Comparator.naturalOrder());
        scopes = List.copyOf(normalized);
        if (mode == Mode.SCOPED && scopes.isEmpty()) {
            throw new IllegalArgumentException("scoped dataflow requires --dataflow-scope");
        }
        if (mode != Mode.SCOPED && !scopes.isEmpty()) {
            throw new IllegalArgumentException(
                    "dataflow scopes require --dataflow-mode=scoped");
        }
    }

    public static FlowProfile off() {
        return new FlowProfile(Mode.OFF, List.of());
    }

    public static FlowProfile full() {
        return new FlowProfile(Mode.FULL, List.of());
    }

    public boolean enabled() {
        return mode != Mode.OFF;
    }

    public boolean detailed(String methodId, String sourceFile) {
        if (mode == Mode.FULL) return true;
        if (mode != Mode.SCOPED) return false;
        String logicalMethod = logicalMethod(methodId);
        String normalizedSource = sourceFile == null ? "" : sourceFile.replace('\\', '/');
        for (String selector : scopes) {
            int separator = selector.indexOf(':');
            String kind = selector.substring(0, separator);
            String pattern = selector.substring(separator + 1);
            String candidate = "source".equals(kind) ? normalizedSource : logicalMethod;
            if (globMatches(pattern, candidate)) return true;
        }
        return false;
    }

    public String fingerprint() {
        return mode.name().toLowerCase(Locale.ROOT) + "|" + String.join(",", scopes);
    }

    private static String logicalMethod(String methodId) {
        if (methodId == null) return "";
        int separator = methodId.lastIndexOf("::");
        return separator < 0 ? methodId : methodId.substring(separator + 2);
    }

    /** Linear-space wildcard matcher; '*' and repeated '**' both match any characters. */
    static boolean globMatches(String pattern, String value) {
        int p = 0;
        int v = 0;
        int star = -1;
        int retry = -1;
        while (v < value.length()) {
            if (p < pattern.length()
                    && (pattern.charAt(p) == '?' || pattern.charAt(p) == value.charAt(v))) {
                p++;
                v++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                while (p < pattern.length() && pattern.charAt(p) == '*') p++;
                star = p;
                retry = v;
            } else if (star >= 0) {
                p = star;
                v = ++retry;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }
}
