package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.semantic.SmellDetector;
import com.anatomist.store.SqliteStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "lint",
        mixinStandardHelpOptions = true,
        description = "Detect architecture smells based on arch_roles + call graph.")
public class LintCommand implements Callable<Integer> {

    @Option(names = "--arch-smell", description = "Run architecture smell detection.", defaultValue = "true")
    boolean archSmell;

    @Option(names = "--index", description = "Path to index.db.")
    Path index;

    @Option(names = "--format", description = "Output format: markdown (default) | json.", defaultValue = "markdown")
    String format;

    @Override
    public Integer call() throws Exception {
        Path db = IndexPath.resolve(index);

        try (SqliteStore store = new SqliteStore(db)) {
            SmellDetector detector = new SmellDetector(store);
            List<SmellDetector.Smell> smells = detector.detect();

            if ("json".equalsIgnoreCase(format)) {
                printJson(smells);
                return 0;
            }

            if (smells.isEmpty()) {
                System.out.println("No architecture smells detected.");
                return 0;
            }

            printMarkdown(smells);
            return smells.isEmpty() ? 0 : 0;
        }
    }

    private void printMarkdown(List<SmellDetector.Smell> smells) {
        Map<String, List<SmellDetector.Smell>> byType = new LinkedHashMap<>();
        for (SmellDetector.Smell s : smells) {
            byType.computeIfAbsent(s.type, k -> new java.util.ArrayList<>()).add(s);
        }

        System.out.println("# Architecture Smell Report\n");
        System.out.println("Found **" + smells.size() + "** smell(s) in **" + byType.size() + "** category(ies).\n");

        for (Map.Entry<String, List<SmellDetector.Smell>> entry : byType.entrySet()) {
            System.out.println("## SMELL: " + entry.getKey() + "\n");
            for (SmellDetector.Smell s : entry.getValue()) {
                System.out.println("  " + s.nodeLabel + " (`" + s.nodeId + "`)");
                System.out.println("  " + s.description);
                System.out.println("  → suggest: " + s.suggestion);
                System.out.println();
            }
        }
    }

    private void printJson(List<SmellDetector.Smell> smells) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (SmellDetector.Smell s : smells) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", s.type);
            row.put("node_id", s.nodeId);
            row.put("node_label", s.nodeLabel);
            row.put("description", s.description);
            row.put("suggestion", s.suggestion);
            rows.add(row);
            byType.merge(s.type, 1, Integer::sum);
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", smells.size());
        stats.put("types", byType);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("command", "lint");
        out.put("status", "ok");
        out.put("results", rows);
        out.put("stats", stats);
        out.put("warnings", List.of());
        out.put("errors", List.of());
        System.out.println(Json.writePretty(out));
    }
}
