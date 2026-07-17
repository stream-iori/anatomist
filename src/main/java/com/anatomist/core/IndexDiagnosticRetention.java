package com.anatomist.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Shared bounded-retention contract for persisted and immediately emitted diagnostics. */
public final class IndexDiagnosticRetention {

    public static final int LIMIT = 5_000;

    private IndexDiagnosticRetention() {}

    public static List<IndexDiagnostic> retain(List<IndexDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) return List.of();
        if (diagnostics.size() <= LIMIT) return List.copyOf(diagnostics);

        List<IndexDiagnostic> ordered = new ArrayList<>(diagnostics);
        ordered.sort(Comparator
                .comparingInt(IndexDiagnosticRetention::priority)
                .thenComparing(Comparator.comparingLong(IndexDiagnostic::count).reversed())
                .thenComparing(IndexDiagnostic::code,
                        Comparator.nullsLast(String::compareTo))
                .thenComparing(IndexDiagnostic::sourceFile,
                        Comparator.nullsLast(String::compareTo)));
        int omitted = ordered.size() - (LIMIT - 1);
        List<IndexDiagnostic> retained =
                new ArrayList<>(ordered.subList(0, LIMIT - 1));
        retained.add(new IndexDiagnostic(
                "warning", "DIAGNOSTIC_STORAGE_TRUNCATED", "RESOLUTION",
                null, null, null, null, omitted,
                "Detailed resolution diagnostics exceeded the persisted limit; "
                        + "coverage for resolution-dependent negative queries is unknown."));
        return List.copyOf(retained);
    }

    private static int priority(IndexDiagnostic diagnostic) {
        if (diagnostic == null || diagnostic.severity() == null) return 3;
        return switch (diagnostic.severity().toLowerCase(Locale.ROOT)) {
            case "error" -> 0;
            case "warning" -> 1;
            default -> 2;
        };
    }
}
