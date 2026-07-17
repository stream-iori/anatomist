package com.anatomist.core;

import com.anatomist.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Lossless aggregate of analysis gaps, independent from bounded diagnostic samples. */
public final class AnalysisCoverage {

    public record Row(String sourceFile,
                      String module,
                      String scope,
                      String capability,
                      String status,
                      long occurrences,
                      long groups,
                      String codes,
                      String codeCounts,
                      boolean detailsTruncated) {}

    private static final List<String> CAPABILITIES = List.of(
            "DECLARATION", "CALL_OUTGOING", "CALL_INCOMING", "CALL_PATH",
            "TYPE_OUTGOING", "TYPE_INCOMING",
            "REFERENCE_OUTGOING", "REFERENCE_INCOMING",
            "FIELD_ACCESS", "WIRING", "FLOW", "AGGREGATE");

    private AnalysisCoverage() {}

    public static List<Row> summarize(List<IndexDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) return List.of();
        Map<Key, Accumulator> grouped = new TreeMap<>();
        for (IndexDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null) continue;
            for (String capability : CAPABILITIES) {
                if (!relevant(diagnostic, capability)) continue;
                Key key = new Key(value(diagnostic.sourceFile()), value(diagnostic.module()),
                        value(diagnostic.scope()), capability);
                grouped.computeIfAbsent(key, ignored -> new Accumulator()).add(diagnostic);
            }
        }
        List<Row> rows = new ArrayList<>();
        grouped.forEach((key, value) -> rows.add(new Row(
                key.sourceFile(), key.module(), key.scope(), key.capability(),
                "partial", value.occurrences, value.groups,
                Json.writeCompact(value.codes),
                Json.writeCompact(value.codeCounts),
                value.detailsTruncated)));
        return rows;
    }

    private static boolean relevant(IndexDiagnostic diagnostic, String capability) {
        String code = upper(diagnostic.code());
        String phase = upper(diagnostic.phase());
        if ("UNRESOLVED_SYMBOLS".equals(code)) return false;
        if ("JAVA_PARSE_FAILED".equals(code) || "DANGLING_FACTS_DROPPED".equals(code)) {
            return true;
        }
        if ("DIAGNOSTIC_STORAGE_TRUNCATED".equals(code)) {
            return !"DECLARATION".equals(capability);
        }
        if (code.startsWith("CLASSPATH_")) return !"DECLARATION".equals(capability);
        return switch (capability) {
            case "DECLARATION" -> false;
            case "CALL_OUTGOING", "CALL_INCOMING", "CALL_PATH" ->
                    phase.contains("CALL_GRAPH") || phase.contains("METHOD");
            case "TYPE_OUTGOING", "TYPE_INCOMING" ->
                    phase.contains("HIERARCHY") || phase.contains("REFERENCE");
            case "REFERENCE_OUTGOING", "REFERENCE_INCOMING" ->
                    phase.contains("REFERENCE") || phase.contains("METHOD")
                            || phase.contains("FIELD");
            case "FIELD_ACCESS" -> phase.contains("FIELD_ACCESS");
            case "WIRING" -> phase.contains("ANNOTATION") || phase.contains("REFERENCE");
            case "FLOW" -> phase.contains("FLOW") || phase.contains("CALL_GRAPH")
                    || phase.contains("METHOD");
            case "AGGREGATE" -> resolutionFailure(diagnostic);
            default -> false;
        };
    }

    private static boolean resolutionFailure(IndexDiagnostic diagnostic) {
        String code = upper(diagnostic.code());
        String phase = upper(diagnostic.phase());
        return phase.contains("RESOLUTION") || phase.contains("CALL_GRAPH")
                || phase.contains("HIERARCHY") || phase.contains("REFERENCE")
                || phase.contains("METHOD") || phase.contains("FIELD")
                || code.endsWith("_NOT_FOUND") || code.endsWith("_RESOLUTION")
                || code.contains("INFERENCE") || code.contains("OVERLOAD")
                || code.contains("SYMBOL_MISSING") || code.startsWith("CLASSPATH_");
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "*" : value;
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private record Key(String sourceFile, String module, String scope, String capability)
            implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int result = sourceFile.compareTo(other.sourceFile);
            if (result != 0) return result;
            result = module.compareTo(other.module);
            if (result != 0) return result;
            result = scope.compareTo(other.scope);
            if (result != 0) return result;
            return capability.compareTo(other.capability);
        }
    }

    private static final class Accumulator {
        private long occurrences;
        private long groups;
        private final TreeSet<String> codes = new TreeSet<>();
        private final Map<String, Long> codeCounts = new TreeMap<>();
        private boolean detailsTruncated;

        void add(IndexDiagnostic diagnostic) {
            long count = Math.max(0L, diagnostic.count());
            occurrences += count;
            groups++;
            String code = diagnostic.code() == null ? "UNKNOWN" : diagnostic.code();
            codes.add(code);
            codeCounts.merge(code, count, Long::sum);
            detailsTruncated |= "DIAGNOSTIC_STORAGE_TRUNCATED".equals(code);
        }
    }
}
