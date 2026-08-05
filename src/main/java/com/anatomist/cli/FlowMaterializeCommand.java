package com.anatomist.cli;

import com.anatomist.flow.FlowMaterializationException;
import com.anatomist.flow.FlowMaterializer;
import com.anatomist.json.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** Explicitly writes DETAIL facts for one bounded structural call path. */
@Command(name = "flow-materialize", mixinStandardHelpOptions = true,
        description = "Build DETAIL flow facts for source files on one shortest static call path.")
public final class FlowMaterializeCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Source method ref.") String source;
    @Parameters(index = "1", description = "Target method ref.") String target;
    @Option(names = "--depth", defaultValue = "8",
            description = "Static call-path depth (1..20, default 8); no write occurs when an empty path is truncated.")
    int depth;
    @Option(names = "--through-callbacks", description = "Follow lambda and anonymous callback bodies.")
    boolean throughCallbacks;
    @Option(names = "--module", description = "Restrict endpoint resolution to one module.") String module;
    @Option(names = "--scope", defaultValue = "MAIN", description = "MAIN | TEST | GENERATED | ALL.") String scope;
    @Option(names = "--verify-content", description = "Hash indexed source files before writing flow facts.")
    boolean verifyContent;
    @Option(names = "--timings", description = "Reserved for a stable timing contract.") boolean timings;
    @Option(names = "--format", defaultValue = "text", description = "Output format: text | json.") String format;
    @Option(names = "--index") Path index;

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        long started = System.nanoTime();
        try {
            FlowMaterializer.Result result = new FlowMaterializer(db).materialize(source, target,
                    depth, throughCallbacks, module, scope, verifyContent);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("command", "flow-materialize");
            out.put("status", "ok");
            out.put("index_path", db.toString());
            out.put("mode", result.noOp() ? "noop" : "progressive");
            out.put("call_path", result.callPath());
            out.put("stats", Map.of(
                    "selected_files", result.selectedFiles(),
                    "selected_methods", result.selectedMethods(),
                    "flow_nodes", result.stats().nodes(),
                    "flow_edges", result.stats().edges(),
                    "flow_summaries", result.stats().summaries(),
                    "flow_detailed_methods", result.stats().detailedMethods(),
                    "flow_summary_only_methods", result.stats().summaryOnlyMethods()));
            out.put("next_commands", List.of("anatomist flow-path " + source + " " + target
                    + " --depth " + depth + " --index " + db));
            if (timings) out.put("timings_ms", Map.of("total", (System.nanoTime() - started) / 1_000_000L));
            emit(out);
            return 0;
        } catch (FlowMaterializationException failure) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("command", "flow-materialize");
            out.put("status", "error");
            out.put("code", failure.code());
            out.put("message", failure.getMessage());
            if ("FLOW_MATERIALIZATION_DEPTH_TRUNCATED".equals(failure.code()) && depth < 20) {
                out.put("next_commands", List.of("anatomist flow-materialize " + source + " " + target
                        + " --depth " + Math.min(20, depth * 2) + " --index " + db));
            } else if ("INDEX_STALE".equals(failure.code())) {
                out.put("next_commands", List.of("anatomist index . --incremental --health-policy integrity"
                        + " --format json --output " + db));
            }
            emit(out);
            return 2;
        }
    }

    private void emit(Map<String, Object> out) {
        if ("json".equalsIgnoreCase(format)) {
            System.out.println(Json.writePretty(out));
            return;
        }
        System.out.println(out.get("status").equals("ok")
                ? "Flow materialized: " + out.get("mode") : "ERROR: " + out.get("code"));
    }
}
