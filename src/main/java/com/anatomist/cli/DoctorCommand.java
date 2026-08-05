package com.anatomist.cli;

import com.anatomist.store.FileCacheService;
import com.anatomist.json.Json;
import com.anatomist.store.IndexStateStore;
import com.anatomist.store.SqliteStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "doctor",
        mixinStandardHelpOptions = true,
        description = "Report anatomist runtime, index, schema, and Agent-facing capabilities.")
public class DoctorCommand implements Callable<Integer> {

    @Option(names = "--format", description = "Output format: json | text.", defaultValue = "text")
    String format;

    @Option(names = "--index", description = "Path to index.db. Defaults to ~/.anatomist/indexes/<repo-key>/index.db.")
    Path index;

    @Option(names = "--strict-health", description = "Return exit code 3 unless index health is healthy.")
    boolean strictHealth;

    @Option(names = "--health-policy",
            description = "Health gate: none | integrity | complete. --strict-health aliases complete.")
    String healthPolicy;

    @Option(names = "--diagnostic-file", description = "Filter diagnostics by source-file substring.")
    String diagnosticFile;

    @Option(names = "--diagnostic-code", description = "Filter diagnostics by exact reason/code.")
    String diagnosticCode;

    @Option(names = "--diagnostic-scope", description = "Filter diagnostics by source scope.")
    String diagnosticScope;

    @Option(names = "--diagnostic-module", description = "Filter diagnostics by module.")
    String diagnosticModule;

    @Option(names = "--diagnostic-phase", description = "Filter diagnostics by extraction phase.")
    String diagnosticPhase;

    @Option(names = "--offset", description = "Diagnostic result offset.", defaultValue = "0")
    int offset;

    @Option(names = "--limit", description = "Maximum diagnostics returned.", defaultValue = "100")
    int limit;

    @Option(names = "--agent-preflight",
            description = "Read-only Agent readiness report with blockers and suggested index commands.")
    boolean agentPreflight;

    @Override
    public Integer call() {
        com.anatomist.core.HealthPolicy policy;
        try {
            policy = com.anatomist.core.HealthPolicy.resolve(strictHealth, healthPolicy);
        } catch (IllegalArgumentException invalid) {
            System.err.println("ERROR: " + invalid.getMessage());
            return 2;
        }
        Path defaultPath = DefaultIndexPath.forQueryRead(Path.of("").toAbsolutePath());
        Path db = index == null ? defaultPath : index.toAbsolutePath().normalize();
        boolean exists = Files.isRegularFile(db);

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("command", "doctor");
        out.put("status", "ok");
        out.put("version", BuildVersion.display());
        out.put("schema_version", FileCacheService.CURRENT_SCHEMA_VERSION);
        out.put("default_index_path", defaultPath.toString());
        out.put("index_path", db.toString());
        out.put("index_exists", exists);
        out.put("index_state", exists ? "unknown" : "missing");
        IndexStateStore.Snapshot freshness = IndexStateStore.read(db);
        out.put("freshness_state", freshness.state().name().toLowerCase());
        if (freshness.reason() != null) out.put("rebuild_reason", freshness.reason());
        if (freshness.dirtyGeneration() > 0) out.put("dirty_generation", freshness.dirtyGeneration());
        out.put("commands", List.of(
                "index", "index-docs", "watch", "search", "context", "callees-of",
                "callers-of", "branches-of", "bean-config", "hierarchy", "implementors-of", "deps-of", "used-by",
                "field-access", "call-path", "overview", "survey-baseline",
                "flow-of", "flow-path", "flow-materialize", "taint-path", "exception-flow", "guards-of",
                "flow-summary", "annotate", "doctor"));
        out.put("capabilities", List.of(
                "json-query-output", "index-json-summary",
                "spring-beans", "spring-mvc-routes", "spring-xml", "spring-xml-config-tree",
                "branch-context-slices", "source-snapshot-fingerprint",
                "core-reflection",
                "cfg", "def-use", "interprocedural-flow", "exception-flow", "taint-flow",
                "progressive-dataflow", "agent-preflight"));

        if (exists) {
            try (SqliteStore store = new SqliteStore(db)) {
                out.put("index_schema_version", store.schemaVersion());
                if (!store.schemaCompatible()) {
                    out.put("index_state", "incompatible");
                    out.put("status", "degraded");
                    com.anatomist.core.IndexDiagnostic mismatch =
                            new com.anatomist.core.IndexDiagnostic(
                                    "error", "SCHEMA_MISMATCH", "SCHEMA",
                                    null, null, null, null, 1,
                                    "required=" + FileCacheService.CURRENT_SCHEMA_VERSION
                                            + ", actual=" + store.schemaVersion());
                    addHealth(out, com.anatomist.core.IndexHealthReport.of(List.of(mismatch)),
                            policy);
                } else {
                    store.readProjectMeta("java_version").ifPresent(v -> out.put("java_version", v));
                    store.readProjectMeta("java_version_source")
                            .ifPresent(v -> out.put("java_version_source", v));
                    store.readProjectMeta("classpath_mode").ifPresent(v -> out.put("classpath_mode", v));
                    Map<String, Object> classpathDetection = new java.util.LinkedHashMap<>();
                    store.readProjectMeta("classpath_detection_status")
                            .ifPresent(v -> classpathDetection.put("status", v));
                    store.readProjectMeta("classpath_detection_status")
                            .ifPresent(v -> classpathDetection.put("origin", classpathOrigin(v)));
                    putIntegerMeta(store, classpathDetection,
                            "classpath_detection_entries", "entries");
                    putIntegerMeta(store, classpathDetection,
                            "classpath_detection_module_outputs", "module_output_files");
                    putIntegerMeta(store, classpathDetection,
                            "classpath_detection_maven_exit", "maven_exit_code");
                    store.readProjectMeta("classpath_detection_error_sample")
                            .filter(v -> !v.isBlank())
                            .ifPresent(v -> classpathDetection.put("error_sample", v));
                    if (!classpathDetection.isEmpty()) {
                        out.put("classpath_detection", classpathDetection);
                    }
                    store.readProjectMeta("spring_xml")
                            .ifPresent(v -> out.put("spring_xml", Boolean.parseBoolean(v)));
                    store.readProjectMeta("dataflow_mode")
                            .ifPresent(v -> out.put("dataflow_mode", v));
                    store.readProjectMeta("dataflow_scopes")
                            .ifPresent(v -> out.put("dataflow_scopes",
                                    v.isBlank() ? List.of() : List.of(v.split(","))));
                    store.readProjectMeta("source_root").ifPresent(v -> out.put("source_root", v));
                    store.readProjectMeta(com.anatomist.core.ProjectMetadata.SNAPSHOT_FINGERPRINT_KEY)
                            .ifPresent(v -> out.put("source_snapshot_fingerprint", v));
                    addSnapshotStatus(out, store);
                    if (store.readProjectMeta("source_git_commit").isPresent()
                            && out.get("source_root") instanceof String sourceRoot) {
                        com.anatomist.core.ProjectMetadata.GitUntrackedCache cache =
                                com.anatomist.core.ProjectMetadata.gitUntrackedCache(Path.of(sourceRoot));
                        out.put("git_untracked_cache", cache.value());
                        if (cache != com.anatomist.core.ProjectMetadata.GitUntrackedCache.ENABLED) {
                            out.put("advice", List.of(
                                    "Enable faster exact Git dirty checks with: "
                                            + "git config core.untrackedCache true"));
                        }
                    }
                    Map<String, Long> nodeKinds = store.queryKindCounts();
                    Map<String, Long> relations = store.queryRelationCounts();
                    out.put("node_kinds", nodeKinds);
                    out.put("relations", relations);
                    if (nodeKinds.isEmpty() || store.readFileCache().isEmpty()) {
                        out.put("index_state", "empty");
                        out.put("status", "degraded");
                        com.anatomist.core.IndexDiagnostic empty =
                                new com.anatomist.core.IndexDiagnostic(
                                        "error", "INDEX_EMPTY", "GRAPH_INTEGRITY",
                                        null, null, null, null, 1,
                                        "index schema exists but no committed graph is available");
                        addHealth(out, com.anatomist.core.IndexHealthReport.of(List.of(empty)),
                                policy);
                        addAgentPreflight(out, db, exists);
                        return emit(out, db, exists, policy);
                    }
                    out.put("index_state", "committed");
                    com.anatomist.core.IndexHealthReport health =
                            com.anatomist.core.IndexHealthService.read(store);
                    List<com.anatomist.core.IndexDiagnostic> filtered = health.diagnostics().stream()
                            .filter(this::matchesDiagnostic)
                            .toList();
                    int safeOffset = Math.max(0, offset);
                    int safeLimit = Math.max(1, Math.min(limit, 1000));
                    List<com.anatomist.core.IndexDiagnostic> page = filtered.stream()
                            .skip(safeOffset).limit(safeLimit).toList();
                    com.anatomist.core.IndexHealthReport displayed =
                            new com.anatomist.core.IndexHealthReport(health.status(), page);
                    out.put("health", health.status().name().toLowerCase());
                    out.put("health_dimensions", health.dimensions());
                    Map<String, Long> coverageCounts = store.readResolutionDiagnosticCounts();
                    out.put("resolution_diagnostic_counts", coverageCounts.isEmpty()
                            ? resolutionCounts(health.diagnostics()) : coverageCounts);
                    out.put("resolution_diagnostic_groups", resolutionGroups(health.diagnostics()));
                    out.put("gate", health.gate(policy).toMap());
                    out.put("diagnostics", displayed.toMaps());
                    out.put("warnings", displayed.warnings());
                    out.put("errors", displayed.errors());
                    out.put("diagnostic_stats", Map.of(
                            "total", health.diagnostics().size(),
                            "matched", filtered.size(),
                            "offset", safeOffset,
                            "limit", safeLimit,
                            "truncated", safeOffset + page.size() < filtered.size()));
                }
            } catch (RuntimeException ex) {
                out.put("status", "degraded");
                out.put("warning", ex.getMessage());
            }
        }

        addAgentPreflight(out, db, exists);
        return emit(out, db, exists, policy);
    }

    /** A deliberately read-only readiness contract for Agent query workflows. */
    private void addAgentPreflight(Map<String, Object> out, Path db, boolean exists) {
        if (!agentPreflight) return;
        Map<String, Object> preflight = new java.util.LinkedHashMap<>();
        java.util.List<String> blockers = new java.util.ArrayList<>();
        java.util.List<String> warnings = new java.util.ArrayList<>();
        java.util.List<String> next = new java.util.ArrayList<>();
        String state = String.valueOf(out.get("index_state"));
        String root = out.get("source_root") instanceof String value && !value.isBlank()
                ? value : Path.of("").toAbsolutePath().normalize().toString();
        if (!exists || "missing".equals(state)) {
            blockers.add("INDEX_MISSING");
            next.add(indexCommand(root, db, false));
        } else if ("incompatible".equals(state) || "empty".equals(state)) {
            blockers.add("incompatible".equals(state) ? "SCHEMA_MISMATCH" : "INDEX_EMPTY");
            next.add(indexCommand(root, db, true));
        } else {
            if (out.get("source_snapshot") instanceof Map<?, ?> snapshot
                    && Boolean.FALSE.equals(snapshot.get("match"))) {
                blockers.add("INDEX_STALE");
                next.add(indexCommand(root, db, false));
            }
            if (out.get("health_dimensions") instanceof Map<?, ?> dimensions) {
                if (dimensions.get("parse") instanceof Map<?, ?> parse
                        && !"complete".equals(parse.get("status"))) {
                    blockers.add("PARSE_INCOMPLETE");
                }
                if (dimensions.get("graph_integrity") instanceof Map<?, ?> graph
                        && !"healthy".equals(graph.get("status"))) {
                    blockers.add("GRAPH_INTEGRITY_FAILED");
                }
            }
            if (out.get("health_dimensions") instanceof Map<?, ?> dimensions
                    && dimensions.get("resolution") instanceof Map<?, ?> resolution
                    && !"complete".equals(resolution.get("status"))) {
                warnings.add("RESOLUTION_PARTIAL");
            }
            if (out.get("gate") instanceof Map<?, ?> gate
                    && Boolean.FALSE.equals(gate.get("passed"))
                    && !"none".equals(gate.get("policy"))) {
                blockers.add("INDEX_INTEGRITY_FAILED");
            }
        }
        Map<String, Object> flow = new java.util.LinkedHashMap<>();
        if (out.containsKey("dataflow_mode")) flow.put("configured_mode", out.get("dataflow_mode"));
        if (exists && "committed".equals(state)) {
            try (SqliteStore store = new SqliteStore(db)) {
                com.anatomist.flow.FlowPersistence.Stats stats =
                        com.anatomist.flow.FlowPersistence.stats(store);
                flow.put("detailed_methods", stats.detailedMethods());
                flow.put("summary_only_methods", stats.summaryOnlyMethods());
                flow.put("progressive", "off".equals(out.get("dataflow_mode"))
                        && stats.detailedMethods() > 0);
                Map<String, com.anatomist.model.FileCacheEntry> cache = store.readFileCache();
                List<Path> files = cache.keySet().stream().filter(path -> path.endsWith(".java"))
                        .map(Path.of(root)::resolve).filter(Files::isRegularFile).toList();
                com.anatomist.store.FileCacheService.CandidateScan scan =
                        new com.anatomist.store.FileCacheService().detectChangesFast(
                                Path.of(root), files, cache, false, null);
                if (!scan.changes().isEmpty() && !blockers.contains("INDEX_STALE")) {
                    blockers.add("INDEX_STALE");
                    next.add(indexCommand(root, db, false));
                }
            }
        }
        preflight.put("status", !blockers.isEmpty() ? "REPAIR_REQUIRED"
                : warnings.isEmpty() ? "READY" : "DEGRADED");
        preflight.put("blockers", blockers);
        preflight.put("warnings", warnings);
        preflight.put("next_commands", next);
        preflight.put("flow_coverage", flow);
        out.put("agent_preflight", preflight);
    }

    private static String indexCommand(String root, Path db, boolean recreate) {
        return "anatomist index " + root + (recreate ? " --recreate" : " --incremental")
                + " --health-policy integrity --format json --output " + db;
    }

    private boolean matchesDiagnostic(com.anatomist.core.IndexDiagnostic diagnostic) {
        return contains(diagnostic.sourceFile(), diagnosticFile)
                && equalsIgnoreCase(diagnostic.code(), diagnosticCode)
                && equalsIgnoreCase(diagnostic.scope(), diagnosticScope)
                && equalsIgnoreCase(diagnostic.module(), diagnosticModule)
                && equalsIgnoreCase(diagnostic.phase(), diagnosticPhase);
    }

    private static boolean contains(String actual, String filter) {
        return filter == null || filter.isBlank()
                || actual != null && actual.contains(filter);
    }

    private static boolean equalsIgnoreCase(String actual, String filter) {
        return filter == null || filter.isBlank()
                || actual != null && actual.equalsIgnoreCase(filter);
    }

    private static void putIntegerMeta(SqliteStore store, Map<String, Object> out,
                                       String metaKey, String outputKey) {
        store.readProjectMeta(metaKey).filter(value -> !value.isBlank()).ifPresent(value -> {
            try {
                out.put(outputKey, Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
                out.put(outputKey, value);
            }
        });
    }

    private static String classpathOrigin(String status) {
        if (status == null) return "unknown";
        return switch (status.toLowerCase(java.util.Locale.ROOT)) {
            case "index_metadata" -> "index_metadata";
            case "cache_hit" -> "classpath_cache";
            case "full", "partial" -> "maven";
            case "explicit" -> "explicit";
            case "not_requested" -> "none";
            default -> "unknown";
        };
    }

    private static void addSnapshotStatus(Map<String, Object> out, SqliteStore store) {
        String root = store.readProjectMeta("source_root").orElse("");
        String indexed = store.readProjectMeta("source_git_commit").orElse("");
        String indexedAt = store.readProjectMeta("indexed_at").orElse("");
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        if (!indexed.isBlank()) snapshot.put("indexed_git_commit", indexed);
        if (!indexedAt.isBlank()) snapshot.put("indexed_at", indexedAt);
        String current = root.isBlank() ? null
                : com.anatomist.core.ProjectMetadata.currentGitCommit(Path.of(root));
        if (current != null && !current.isBlank()) snapshot.put("current_git_commit", current);
        snapshot.put("match", !indexed.isBlank() && current != null && !current.isBlank()
                ? indexed.equals(current) : null);
        if (!snapshot.isEmpty()) out.put("source_snapshot", snapshot);
    }

    private static Map<String, Long> resolutionCounts(
            List<com.anatomist.core.IndexDiagnostic> diagnostics) {
        Map<String, Long> counts = new java.util.TreeMap<>();
        for (com.anatomist.core.IndexDiagnostic diagnostic : diagnostics) {
            if (com.anatomist.core.ResolutionDiagnostics.isReasonCode(diagnostic.code())
                    || "UNRESOLVED_SYMBOLS".equals(diagnostic.code())) {
                counts.merge(diagnostic.code(), diagnostic.count(), Long::sum);
            }
        }
        return counts;
    }

    private static Map<String, Long> resolutionGroups(
            List<com.anatomist.core.IndexDiagnostic> diagnostics) {
        Map<String, Long> groups = new java.util.TreeMap<>();
        for (com.anatomist.core.IndexDiagnostic diagnostic : diagnostics) {
            if (com.anatomist.core.ResolutionDiagnostics.isReasonCode(diagnostic.code())
                    || "UNRESOLVED_SYMBOLS".equals(diagnostic.code())) {
                groups.merge(diagnostic.code(), 1L, Long::sum);
            }
        }
        return groups;
    }

    private static void addHealth(Map<String, Object> out,
                                  com.anatomist.core.IndexHealthReport health,
                                  com.anatomist.core.HealthPolicy policy) {
        out.put("health", health.status().name().toLowerCase());
        out.put("health_dimensions", health.dimensions());
        out.put("gate", health.gate(policy).toMap());
        out.put("diagnostics", health.toMaps());
        out.put("warnings", health.warnings());
        out.put("errors", health.errors());
    }

    private int emit(Map<String, Object> out, Path db, boolean exists,
                     com.anatomist.core.HealthPolicy policy) {
        if ("json".equalsIgnoreCase(format)) {
            System.out.println(Json.writePretty(out));
        } else {
            System.out.println(BuildVersion.display());
            System.out.println("Index: " + db + (exists ? " (exists)" : " (missing)"));
            System.out.println("Schema: " + FileCacheService.CURRENT_SCHEMA_VERSION);
            if (out.containsKey("index_state")) {
                System.out.println("Index state: " + out.get("index_state"));
            }
            if (out.containsKey("health")) {
                System.out.println("Health: " + out.get("health"));
            }
            if (out.get("gate") instanceof Map<?, ?> gate) {
                System.out.println("Gate: " + gate.get("policy")
                        + " (" + (Boolean.TRUE.equals(gate.get("passed")) ? "passed" : "failed")
                        + ")");
            }
            if (out.containsKey("git_untracked_cache")) {
                System.out.println("Git untracked cache: " + out.get("git_untracked_cache"));
                if (out.containsKey("advice")) {
                    System.out.println("Advice: git config core.untrackedCache true");
                }
            }
        }
        if (policy == com.anatomist.core.HealthPolicy.NONE) return 0;
        if (!"ok".equals(out.get("status"))) return 3;
        if (out.get("gate") instanceof Map<?, ?> gate) {
            return Boolean.TRUE.equals(gate.get("passed")) ? 0 : 3;
        }
        return 3;
    }
}
