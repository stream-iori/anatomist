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

    @Override
    public Integer call() {
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
        IndexStateStore.Snapshot freshness = IndexStateStore.read(db);
        out.put("freshness_state", freshness.state().name().toLowerCase());
        if (freshness.reason() != null) out.put("rebuild_reason", freshness.reason());
        if (freshness.dirtyGeneration() > 0) out.put("dirty_generation", freshness.dirtyGeneration());
        out.put("commands", List.of(
                "index", "index-docs", "watch", "search", "context", "callees-of",
                "callers-of", "branches-of", "bean-config", "hierarchy", "implementors-of", "deps-of", "used-by",
                "field-access", "call-path", "overview", "survey-baseline",
                "flow-of", "flow-path", "taint-path", "exception-flow", "guards-of",
                "flow-summary", "annotate", "doctor"));
        out.put("capabilities", List.of(
                "json-query-output", "index-json-summary",
                "spring-beans", "spring-mvc-routes", "spring-xml", "spring-xml-config-tree",
                "branch-context-slices", "source-snapshot-fingerprint",
                "cfg", "def-use", "interprocedural-flow", "exception-flow", "taint-flow"));

        if (exists) {
            try (SqliteStore store = new SqliteStore(db)) {
                out.put("index_schema_version", store.schemaVersion());
                if (!store.schemaCompatible()) {
                    out.put("status", "degraded");
                    out.put("health", "unhealthy");
                    out.put("errors", List.of(Map.of("code", "SCHEMA_MISMATCH",
                            "required", FileCacheService.CURRENT_SCHEMA_VERSION,
                            "actual", store.schemaVersion())));
                } else {
                    store.readProjectMeta("java_version").ifPresent(v -> out.put("java_version", v));
                    store.readProjectMeta("java_version_source")
                            .ifPresent(v -> out.put("java_version_source", v));
                    store.readProjectMeta("classpath_mode").ifPresent(v -> out.put("classpath_mode", v));
                    store.readProjectMeta("spring_xml")
                            .ifPresent(v -> out.put("spring_xml", Boolean.parseBoolean(v)));
                    store.readProjectMeta("source_root").ifPresent(v -> out.put("source_root", v));
                    store.readProjectMeta(com.anatomist.core.ProjectMetadata.SNAPSHOT_FINGERPRINT_KEY)
                            .ifPresent(v -> out.put("source_snapshot_fingerprint", v));
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
                        out.put("status", "degraded");
                        out.put("health", "unhealthy");
                        out.put("diagnostics", List.of());
                        out.put("warnings", List.of());
                        out.put("errors", List.of(Map.of("code", "INDEX_EMPTY",
                                "message", "index schema exists but no committed graph is available")));
                        return emit(out, db, exists);
                    }
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

        return emit(out, db, exists);
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

    private int emit(Map<String, Object> out, Path db, boolean exists) {
        if ("json".equalsIgnoreCase(format)) {
            System.out.println(Json.writePretty(out));
        } else {
            System.out.println(BuildVersion.display());
            System.out.println("Index: " + db + (exists ? " (exists)" : " (missing)"));
            System.out.println("Schema: " + FileCacheService.CURRENT_SCHEMA_VERSION);
            if (out.containsKey("git_untracked_cache")) {
                System.out.println("Git untracked cache: " + out.get("git_untracked_cache"));
                if (out.containsKey("advice")) {
                    System.out.println("Advice: git config core.untrackedCache true");
                }
            }
        }
        boolean unhealthy = !"ok".equals(out.get("status"))
                || (out.containsKey("health") && !"healthy".equals(out.get("health")));
        return strictHealth && unhealthy ? 3 : 0;
    }
}
