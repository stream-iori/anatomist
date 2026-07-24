package com.anatomist.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
        int capacity = LIMIT - 1;
        int omitted = ordered.size() - capacity;

        // Keep at least one representative of every code before filling the
        // remaining slots by priority/count. Otherwise a high-volume reason
        // can hide a low-volume category and make health dimensions claim it
        // is complete even though the lossless aggregates say otherwise.
        Map<String, IndexDiagnostic> representatives = new LinkedHashMap<>();
        for (IndexDiagnostic diagnostic : ordered) {
            representatives.putIfAbsent(diagnostic.code(), diagnostic);
        }
        List<IndexDiagnostic> retained = new ArrayList<>(capacity);
        Set<IndexDiagnostic> selected = Collections.newSetFromMap(new IdentityHashMap<>());
        for (IndexDiagnostic representative : representatives.values()) {
            if (retained.size() == capacity) break;
            retained.add(representative);
            selected.add(representative);
        }
        for (IndexDiagnostic diagnostic : ordered) {
            if (retained.size() == capacity) break;
            if (selected.add(diagnostic)) retained.add(diagnostic);
        }
        retained.sort(Comparator
                .comparingInt(IndexDiagnosticRetention::priority)
                .thenComparing(Comparator.comparingLong(IndexDiagnostic::count).reversed())
                .thenComparing(IndexDiagnostic::code,
                        Comparator.nullsLast(String::compareTo))
                .thenComparing(IndexDiagnostic::sourceFile,
                        Comparator.nullsLast(String::compareTo)));
        retained.add(new IndexDiagnostic(
                "info", "DIAGNOSTIC_STORAGE_TRUNCATED", "RESOLUTION",
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
