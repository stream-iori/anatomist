package com.anatomist.core;

import java.util.List;
import java.util.Locale;

/** Explainable result of resolving the analysis classpath. */
public record ClasspathDetectionResult(
        Status status,
        List<String> entries,
        Integer mavenExitCode,
        int moduleOutputFiles,
        String errorSample,
        List<IndexDiagnostic> diagnostics
) {
    public enum Status {
        NOT_REQUESTED,
        EXPLICIT,
        CACHE_HIT,
        FULL,
        PARTIAL,
        UNAVAILABLE
    }

    public ClasspathDetectionResult {
        status = status == null ? Status.NOT_REQUESTED : status;
        entries = entries == null ? List.of() : List.copyOf(entries);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static ClasspathDetectionResult notRequested() {
        return new ClasspathDetectionResult(
                Status.NOT_REQUESTED, List.of(), null, 0, null, List.of());
    }

    public static ClasspathDetectionResult explicit(List<String> entries) {
        return new ClasspathDetectionResult(
                Status.EXPLICIT, entries, null, 0, null, List.of());
    }

    public static ClasspathDetectionResult cacheHit(List<String> entries) {
        return new ClasspathDetectionResult(
                Status.CACHE_HIT, entries, null, 0, null, List.of());
    }

    public String wireStatus() {
        return status.name().toLowerCase(Locale.ROOT);
    }
}
