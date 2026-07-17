package com.anatomist.core;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record IndexHealthReport(Status status, List<IndexDiagnostic> diagnostics) {
    public enum Status { HEALTHY, DEGRADED, UNHEALTHY }

    private static final Set<String> INTEGRITY_CODES = Set.of(
            "JAVA_PARSE_FAILED",
            "DANGLING_FACTS_DROPPED",
            "SCHEMA_MISMATCH",
            "INDEX_EMPTY",
            "INDEX_PROMOTION_FAILED");
    private static final Set<String> INTERNAL_CODES = Set.of("INTERNAL_SYMBOL_MISSING");
    private static final Set<String> EXTERNAL_CODES = Set.of(
            "THIRDPARTY_SYMBOL_MISSING",
            "CLASSPATH_PARTIAL",
            "CLASSPATH_UNAVAILABLE");
    private static final Set<String> JDK_CODES = Set.of("JDK_SYMBOL_MISMATCH");
    private static final Set<String> AGGREGATE_CODES = Set.of("UNRESOLVED_SYMBOLS");

    public static IndexHealthReport of(List<IndexDiagnostic> diagnostics) {
        List<IndexDiagnostic> retained = IndexDiagnosticRetention.retain(diagnostics);
        boolean error = retained.stream().anyMatch(d -> "error".equalsIgnoreCase(d.severity()));
        boolean warning = retained.stream().anyMatch(d -> "warning".equalsIgnoreCase(d.severity()));
        return new IndexHealthReport(error ? Status.UNHEALTHY : warning ? Status.DEGRADED : Status.HEALTHY,
                retained);
    }

    public List<Map<String, Object>> toMaps() {
        return diagnostics.stream().map(IndexDiagnostic::toMap).toList();
    }

    public List<Map<String, Object>> warnings() {
        return diagnostics.stream().filter(d -> "warning".equalsIgnoreCase(d.severity()))
                .map(IndexDiagnostic::toMap).toList();
    }

    public List<Map<String, Object>> errors() {
        return diagnostics.stream().filter(d -> "error".equalsIgnoreCase(d.severity()))
                .map(IndexDiagnostic::toMap).toList();
    }

    public HealthGateResult gate(HealthPolicy policy) {
        HealthPolicy effective = policy == null ? HealthPolicy.NONE : policy;
        List<String> blocking = diagnostics.stream()
                .filter(diagnostic -> switch (effective) {
                    case NONE -> false;
                    case INTEGRITY -> INTEGRITY_CODES.contains(diagnostic.code());
                    case COMPLETE -> "warning".equalsIgnoreCase(diagnostic.severity())
                            || "error".equalsIgnoreCase(diagnostic.severity());
                })
                .map(IndexDiagnostic::code)
                .distinct()
                .sorted()
                .toList();
        return new HealthGateResult(effective, blocking.isEmpty(), blocking);
    }

    public Map<String, Object> dimensions() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<IndexDiagnostic> parse = matching(Set.of("JAVA_PARSE_FAILED"));
        List<IndexDiagnostic> graph = matching(Set.of(
                "DANGLING_FACTS_DROPPED", "SCHEMA_MISMATCH",
                "INDEX_EMPTY", "INDEX_PROMOTION_FAILED"));
        out.put("parse", dimension(parse, "complete", "partial"));
        out.put("graph_integrity", dimension(graph, "healthy", "degraded"));

        Map<String, Object> resolution = new LinkedHashMap<>();
        resolution.put("internal", resolutionDimension(matching(INTERNAL_CODES)));
        resolution.put("external", resolutionDimension(matching(EXTERNAL_CODES)));
        resolution.put("jdk", resolutionDimension(matching(JDK_CODES)));
        resolution.put("other", resolutionDimension(diagnostics.stream()
                .filter(d -> isResolutionDiagnostic(d)
                        && !INTERNAL_CODES.contains(d.code())
                        && !EXTERNAL_CODES.contains(d.code())
                        && !JDK_CODES.contains(d.code())
                        && !AGGREGATE_CODES.contains(d.code()))
                .toList()));
        out.put("resolution", resolution);
        return out;
    }

    private List<IndexDiagnostic> matching(Set<String> codes) {
        return diagnostics.stream().filter(d -> codes.contains(d.code())).toList();
    }

    private static Map<String, Object> resolutionDimension(List<IndexDiagnostic> matches) {
        return dimension(matches, "complete", "partial");
    }

    private static Map<String, Object> dimension(List<IndexDiagnostic> matches,
                                                 String healthy,
                                                 String degraded) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", matches.isEmpty() ? healthy : degraded);
        out.put("occurrences", matches.stream().mapToLong(IndexDiagnostic::count).sum());
        out.put("groups", matches.size());
        if (!matches.isEmpty()) {
            out.put("codes", matches.stream().map(IndexDiagnostic::code)
                    .collect(Collectors.toCollection(java.util.TreeSet::new)));
        }
        return out;
    }

    private static boolean isResolutionDiagnostic(IndexDiagnostic diagnostic) {
        if (diagnostic == null) return false;
        String phase = diagnostic.phase() == null ? "" : diagnostic.phase().toUpperCase();
        String code = diagnostic.code() == null ? "" : diagnostic.code();
        return phase.contains("RESOLUTION")
                || phase.contains("CALL_GRAPH")
                || phase.contains("HIERARCHY")
                || phase.contains("REFERENCE")
                || phase.contains("METHOD")
                || phase.contains("FIELD")
                || code.endsWith("_NOT_FOUND")
                || code.endsWith("_RESOLUTION")
                || code.contains("INFERENCE")
                || code.contains("OVERLOAD")
                || "DIAGNOSTIC_STORAGE_TRUNCATED".equals(code);
    }
}
