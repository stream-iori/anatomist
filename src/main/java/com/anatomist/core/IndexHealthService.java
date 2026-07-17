package com.anatomist.core;

import com.anatomist.store.SqliteStore;

import java.util.ArrayList;
import java.util.List;

public final class IndexHealthService {

    private IndexHealthService() {}

    public static IndexHealthReport fromResult(IndexResult result) {
        return IndexHealthReport.of(diagnosticsFromResult(result));
    }

    public static List<IndexDiagnostic> diagnosticsFromResult(IndexResult result) {
        List<IndexDiagnostic> diagnostics = countDiagnostics(
                result.unresolvedCount(), result.droppedDanglingEdges());
        if (result.diagnostics() != null) diagnostics.addAll(result.diagnostics());
        ParseInventory parse = result.parseInventory();
        if (parse != null && parse.failedFiles() > 0) {
            parse.failures().forEach((file, problems) ->
                    diagnostics.add(new IndexDiagnostic(
                            "warning", "JAVA_PARSE_FAILED", "PARSING",
                            file.toString(), null, null, null, 1,
                            problems.isEmpty() ? "parser produced no compilation unit" : problems.get(0))));
        }
        return List.copyOf(diagnostics);
    }

    public static IndexHealthReport fromCounts(long unresolved, long dropped) {
        return IndexHealthReport.of(countDiagnostics(unresolved, dropped));
    }

    private static List<IndexDiagnostic> countDiagnostics(long unresolved, long dropped) {
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
        return diagnostics;
    }

    public static IndexHealthReport read(SqliteStore store) {
        return IndexHealthReport.of(store.readIndexDiagnostics());
    }
}
