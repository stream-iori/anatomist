package com.anatomist.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record QueryEvidence(
        String status,
        String coverage,
        boolean negativeConclusionSafe,
        String code,
        List<String> affectedDimensions,
        Map<String, Long> diagnosticCounts,
        String diagnosticQuery,
        String message
) {
    public QueryEvidence(String status, String coverage, boolean negativeConclusionSafe,
                         String code, List<String> affectedDimensions,
                         Map<String, Long> diagnosticCounts, String diagnosticQuery) {
        this(status, coverage, negativeConclusionSafe, code, affectedDimensions,
                diagnosticCounts, diagnosticQuery, null);
    }

    public QueryEvidence {
        affectedDimensions = affectedDimensions == null ? null : List.copyOf(affectedDimensions);
        diagnosticCounts = diagnosticCounts == null ? null
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(diagnosticCounts));
    }

    public static QueryEvidence positiveComplete() {
        return new QueryEvidence("positive", "complete", true,
                null, null, null, null, null);
    }

    public static QueryEvidence indeterminate(String code, String message) {
        return new QueryEvidence("indeterminate", "incomplete", false,
                code, null, null, null, message);
    }

    public QueryEvidence withPartialFlow(boolean positive) {
        return new QueryEvidence(positive ? status : "indeterminate", "partial", false,
                positive ? code : "FLOW_COVERAGE_INCOMPLETE", affectedDimensions,
                diagnosticCounts, diagnosticQuery, message);
    }

    public QueryEvidence bounded(boolean page, boolean depth, boolean limit) {
        if (!page && !depth && !limit) return this;
        java.util.Set<String> dimensions = new java.util.TreeSet<>(
                affectedDimensions == null ? List.of() : affectedDimensions);
        if (page) dimensions.add("query_page");
        if (depth) dimensions.add("query_depth");
        if (limit) dimensions.add("query_limit");
        boolean confirmedEmpty = "confirmed_empty".equals(status);
        String boundedCode = confirmedEmpty
                ? depth ? "QUERY_DEPTH_TRUNCATED"
                : limit ? "QUERY_LIMIT_TRUNCATED" : "QUERY_PAGE_INCOMPLETE"
                : code;
        return new QueryEvidence(confirmedEmpty ? "indeterminate" : status,
                coverage, false, boundedCode, List.copyOf(dimensions), diagnosticCounts,
                diagnosticQuery, message);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("coverage", coverage);
        out.put("negative_conclusion_safe", negativeConclusionSafe);
        if (code != null) out.put("code", code);
        if (affectedDimensions != null && !affectedDimensions.isEmpty()) {
            out.put("affected_dimensions", affectedDimensions);
        }
        if (diagnosticCounts != null && !diagnosticCounts.isEmpty()) {
            out.put("diagnostic_counts", diagnosticCounts);
        }
        if (diagnosticQuery != null) out.put("diagnostic_query", diagnosticQuery);
        if (message != null) out.put("message", message);
        return out;
    }
}
