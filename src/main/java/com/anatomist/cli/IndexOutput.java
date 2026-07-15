package com.anatomist.cli;

import com.anatomist.core.IndexConfig;
import com.anatomist.core.IndexHealthReport;
import com.anatomist.core.IndexHealthService;
import com.anatomist.core.IndexResult;
import com.anatomist.incremental.IncrementalIndexer;
import com.anatomist.json.Json;
import com.anatomist.model.GraphConstants;
import com.anatomist.store.FileCacheService;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owns the text/JSON output contract for full and incremental indexing. */
final class IndexOutput {

    private IndexOutput() {}

    static void emitFullJson(IndexResult result, IndexConfig config) {
        emitFullJson(result, config, Map.of());
    }

    static void emitFullJson(IndexResult result, IndexConfig config, Map<String, Long> timingsMs) {
        Map<String, Object> stats = new LinkedHashMap<>();
        Map<String, Long> kinds = result.kindCounts();
        Map<String, Long> relations = result.relationCounts();
        long types = kinds.entrySet().stream()
                .filter(e -> GraphConstants.INDEX_SUMMARY_TYPE_KINDS.contains(e.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
        stats.put("source_files", config.sourceFiles().size());
        stats.put("types", types);
        stats.put("classes", kinds.getOrDefault(GraphConstants.Kind.CLASS, 0L));
        stats.put("methods", kinds.getOrDefault(GraphConstants.Kind.METHOD, 0L));
        stats.put("fields", kinds.getOrDefault(GraphConstants.Kind.FIELD, 0L));
        stats.put("beans", kinds.getOrDefault(GraphConstants.Kind.BEAN, 0L));
        stats.put("unresolved", result.unresolvedCount());
        stats.put("dropped_dangling_edges", result.droppedDanglingEdges());
        stats.put("file_cache_entries", result.fileCacheSize());
        stats.put("elapsed_ms", result.elapsedMs());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("command", "index");
        out.put("status", "ok");
        out.put("schema_version", FileCacheService.CURRENT_SCHEMA_VERSION);
        out.put("index_path", config.dbPath().toString());
        out.put("stats", stats);
        out.put("node_kinds", kinds);
        out.put("relations", relations);
        if (timingsMs != null && !timingsMs.isEmpty()) out.put("timings_ms", timingsMs);
        addHealth(out, IndexHealthService.fromResult(result));
        System.out.println(Json.writePretty(out));
    }

    static void emitTimingsText(Map<String, Long> timingsMs) {
        if (timingsMs == null || timingsMs.isEmpty()) return;
        String rendered = timingsMs.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining(" "));
        System.out.println("  Timings (ms): " + rendered);
    }

    static void emitIncremental(String format, Path projectRoot, Path dbPath,
                                int sourceFileCount, IncrementalIndexer.Summary summary,
                                int fileCacheSize, long elapsedMs) {
        emitIncremental(format, projectRoot, dbPath, sourceFileCount, summary,
                fileCacheSize, elapsedMs, Map.of());
    }

    static void emitIncremental(String format, Path projectRoot, Path dbPath,
                                int sourceFileCount, IncrementalIndexer.Summary summary,
                                int fileCacheSize, long elapsedMs, Map<String, Long> timingsMs) {
        if (!"json".equalsIgnoreCase(format)) {
            emitIncrementalText(projectRoot, dbPath, summary, fileCacheSize, elapsedMs, timingsMs);
            return;
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("source_files", sourceFileCount);
        stats.put("changed_files", summary.changedFiles);
        stats.put("new_files", summary.newFiles);
        stats.put("deleted_files", summary.deletedFiles);
        stats.put("realigned_dependents", summary.realignedDependents);
        stats.put("deleted_nodes", summary.deletedNodes);
        stats.put("deleted_edges", summary.deletedEdges);
        stats.put("written_nodes", summary.writtenNodes);
        stats.put("written_edges", summary.writtenEdges);
        stats.put("unresolved", summary.unresolvedSymbols);
        stats.put("dropped_dangling_edges", summary.droppedDanglingFacts);
        stats.put("file_cache_entries", fileCacheSize);
        stats.put("elapsed_ms", elapsedMs);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("command", "index");
        out.put("status", "ok");
        out.put("mode", "incremental");
        out.put("schema_version", FileCacheService.CURRENT_SCHEMA_VERSION);
        out.put("project_root", projectRoot.toString());
        out.put("index_path", dbPath.toString());
        out.put("stats", stats);
        if (timingsMs != null && !timingsMs.isEmpty()) out.put("timings_ms", timingsMs);
        addHealth(out, IndexHealthService.fromCounts(
                summary.unresolvedSymbols, summary.droppedDanglingFacts));
        System.out.println(Json.writePretty(out));
    }

    private static void emitIncrementalText(Path projectRoot, Path dbPath,
                                            IncrementalIndexer.Summary summary,
                                            int fileCacheSize, long elapsedMs,
                                            Map<String, Long> timingsMs) {
        System.out.println("Indexed " + projectRoot + " (incremental)");
        System.out.println("  Changed files: " + summary.changedFiles);
        System.out.println("  New files:     " + summary.newFiles);
        System.out.println("  Deleted files: " + summary.deletedFiles);
        System.out.println("  Realigned deps:" + summary.realignedDependents);
        System.out.println("  Deleted nodes: " + summary.deletedNodes);
        System.out.println("  Deleted edges: " + summary.deletedEdges);
        System.out.println("  Written nodes: " + summary.writtenNodes);
        System.out.println("  Written edges: " + summary.writtenEdges);
        System.out.println("  Output:        " + dbPath);
        System.out.println("  File cache:    " + fileCacheSize + " entries");
        emitTimingsText(timingsMs);
        System.out.println("Done in " + elapsedMs + "ms");
    }

    private static void addHealth(Map<String, Object> out, IndexHealthReport health) {
        out.put("health", health.status().name().toLowerCase());
        out.put("diagnostics", health.toMaps());
        out.put("warnings", health.warnings());
        out.put("errors", health.errors());
    }
}
