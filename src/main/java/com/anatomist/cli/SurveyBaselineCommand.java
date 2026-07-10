package com.anatomist.cli;

import com.anatomist.store.FileCacheService;
import com.anatomist.query.DtoCodecs;
import com.anatomist.json.Json;
import com.anatomist.query.OverviewResult;
import com.anatomist.query.QueryService;
import com.anatomist.store.SqliteStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "survey-baseline",
        mixinStandardHelpOptions = true,
        description = "Emit a structural project baseline: index metadata, aggregate counts, and structural follow-up queries.")
public class SurveyBaselineCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Project root for query suggestions.")
    Path projectPath;

    @Option(names = "--format", description = "Output format: json | text.", defaultValue = "json")
    String format;

    @Option(names = "--index", description = "Path to index.db (default: ~/.anatomist/<repo>/index.db).")
    Path index;

    @Option(names = "--strict-health", description = "Return exit code 3 unless index health is healthy.")
    boolean strictHealth;

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            DtoCodecs.ensureRegistered();
            OverviewResult overview = q.overview();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("command", "survey-baseline");
            out.put("status", "ok");
            out.put("schema_version", FileCacheService.CURRENT_SCHEMA_VERSION);
            out.put("index_path", db.toString());
            if (projectPath != null) out.put("project_path", projectPath.toAbsolutePath().normalize().toString());
            out.put("overview", overview.toStats());
            out.put("budget", Map.of(
                    "mode", "structural_summary",
                    "packages", overview.packages.size(),
                    "package_deps", overview.packageDeps.size()));
            com.anatomist.core.IndexHealthReport health;
            try (SqliteStore store = new SqliteStore(db)) {
                health =
                        com.anatomist.core.IndexHealthService.read(store);
                out.put("health", health.status().name().toLowerCase());
                out.put("diagnostics", health.toMaps());
                out.put("warnings", health.warnings());
                out.put("errors", health.errors());
            }
            out.put("next_queries", List.of(
                    "anatomist overview --format json --index " + db,
                    "anatomist overview --deps-only --limit 50 --index " + db,
                    "anatomist search <symbol> --limit 50 --index " + db));

            if ("json".equalsIgnoreCase(format)) {
                System.out.println(Json.writePretty(out));
            } else {
                Map<String, Object> stats = overview.toStats();
                System.out.println("survey-baseline: packages=" + stats.get("packages")
                        + " types=" + stats.get("types")
                        + " methods=" + stats.get("methods")
                        + " package_deps=" + stats.get("package_deps"));
            }
            return strictHealth
                    && health.status() != com.anatomist.core.IndexHealthReport.Status.HEALTHY ? 3 : 0;
        }
    }
}
