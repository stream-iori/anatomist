package com.anatomist.cli;

import com.anatomist.store.FileCacheService;
import com.anatomist.json.Json;
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

    @Option(names = "--index", description = "Path to index.db. Defaults to ~/.anatomist/<repo>/index.db.")
    Path index;

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
        out.put("commands", List.of(
                "index", "index-docs", "watch", "search", "context", "callees-of",
                "callers-of", "branches-of", "bean-config", "hierarchy", "implementors-of", "deps-of", "used-by",
                "field-access", "call-path", "overview", "survey-baseline", "export",
                "annotate", "doctor"));
        out.put("capabilities", List.of(
                "json-query-output", "index-json-summary",
                "spring-beans", "spring-mvc-routes", "spring-xml", "spring-xml-config-tree",
                "branch-context-slices"));

        if (exists) {
            try (SqliteStore store = new SqliteStore(db)) {
                store.readProjectMeta("schema_version").ifPresent(v -> out.put("index_schema_version", v));
                store.readProjectMeta("java_version").ifPresent(v -> out.put("java_version", v));
                out.put("node_kinds", store.queryKindCounts());
                out.put("relations", store.queryRelationCounts());
            } catch (RuntimeException ex) {
                out.put("status", "degraded");
                out.put("warning", ex.getMessage());
            }
        }

        if ("json".equalsIgnoreCase(format)) {
            System.out.println(Json.writePretty(out));
        } else {
            System.out.println(BuildVersion.display());
            System.out.println("Index: " + db + (exists ? " (exists)" : " (missing)"));
            System.out.println("Schema: " + FileCacheService.CURRENT_SCHEMA_VERSION);
        }
        return 0;
    }
}
