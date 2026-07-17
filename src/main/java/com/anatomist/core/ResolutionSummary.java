package com.anatomist.core;

import java.util.List;

/** Bounded, explainable symbol-resolution accounting for one extraction pass. */
public record ResolutionSummary(
        long attempted,
        long resolved,
        long unresolved,
        List<IndexDiagnostic> diagnostics
) {
    public ResolutionSummary {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
