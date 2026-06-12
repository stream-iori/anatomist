package com.anatomist.cli;

import com.anatomist.semantic.SmellDetector;
import com.anatomist.store.SqliteStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "lint",
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

            if (smells.isEmpty()) {
                System.out.println("No architecture smells detected.");
                return 0;
            }

            if ("json".equals(format)) {
                printJson(smells);
            } else {
                printMarkdown(smells);
            }
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
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < smells.size(); i++) {
            SmellDetector.Smell s = smells.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"type\":\"").append(escape(s.type)).append("\"");
            sb.append(",\"node_id\":\"").append(escape(s.nodeId)).append("\"");
            sb.append(",\"node_label\":\"").append(escape(s.nodeLabel)).append("\"");
            sb.append(",\"description\":\"").append(escape(s.description)).append("\"");
            sb.append(",\"suggestion\":\"").append(escape(s.suggestion)).append("\"}");
        }
        sb.append("]");
        System.out.println(sb);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
