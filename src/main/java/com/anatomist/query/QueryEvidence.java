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
        String diagnosticQuery
) {
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("coverage", coverage);
        out.put("negative_conclusion_safe", negativeConclusionSafe);
        if (code != null) out.put("code", code);
        out.put("affected_dimensions", affectedDimensions);
        out.put("diagnostic_counts", diagnosticCounts);
        if (diagnosticQuery != null) out.put("diagnostic_query", diagnosticQuery);
        return out;
    }
}
