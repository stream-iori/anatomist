package com.anatomist.core;

import com.anatomist.store.SqliteStore;

import java.util.ArrayList;
import java.util.List;

public final class IndexHealthService {

    private IndexHealthService() {}

    public static IndexHealthReport fromResult(IndexResult result) {
        return fromCounts(result.unresolvedCount(), result.droppedDanglingEdges());
    }

    public static IndexHealthReport fromCounts(long unresolved, long dropped) {
        List<IndexDiagnostic> diagnostics = new ArrayList<>();
        if (unresolved > 0) {
            diagnostics.add(new IndexDiagnostic("info", "UNRESOLVED_SYMBOLS", "RESOLUTION",
                    null, null, null, null, unresolved,
                    "SymbolSolver could not resolve every expression; inspect --debug output when needed."));
        }
        if (dropped > 0) {
            diagnostics.add(new IndexDiagnostic("warning", "DANGLING_FACTS_DROPPED", "EDGE_BINDING",
                    null, null, null, null, dropped,
                    "Internal edges or annotations referenced nodes that were not emitted."));
        }
        return IndexHealthReport.of(diagnostics);
    }

    public static IndexHealthReport read(SqliteStore store) {
        return IndexHealthReport.of(store.readIndexDiagnostics());
    }
}
