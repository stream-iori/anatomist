package com.anatomist.core;

import java.util.List;
import java.util.Map;

public record IndexHealthReport(Status status, List<IndexDiagnostic> diagnostics) {
    public enum Status { HEALTHY, DEGRADED, UNHEALTHY }

    public static IndexHealthReport of(List<IndexDiagnostic> diagnostics) {
        boolean error = diagnostics.stream().anyMatch(d -> "error".equalsIgnoreCase(d.severity()));
        boolean warning = diagnostics.stream().anyMatch(d -> "warning".equalsIgnoreCase(d.severity()));
        return new IndexHealthReport(error ? Status.UNHEALTHY : warning ? Status.DEGRADED : Status.HEALTHY,
                List.copyOf(diagnostics));
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
}
