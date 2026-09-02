package com.anatomist.cli;

import com.anatomist.query.JsonFormatter;
import com.anatomist.query.NodeRow;
import com.anatomist.query.QueryJsonContract;
import com.anatomist.query.SymbolResolutionException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable JSON rendering for selector failures shared by CLI commands. */
final class SymbolResolutionOutput {

    private SymbolResolutionOutput() {}

    static int emit(SymbolResolutionException failure,
                    Path index,
                    String module,
                    String scope) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract_version", QueryJsonContract.VERSION);
        out.put("status", "error");
        out.put("code", failure.code());
        out.put("message", failure.getMessage());
        out.put("selector", failure.resolution().input());
        out.put("selector_status", failure.resolution().status().name());
        List<Map<String, Object>> candidates = failure.resolution().candidates().stream()
                .map(SymbolResolutionOutput::candidate)
                .toList();
        out.put("candidates", candidates);
        out.put("results", candidates);
        out.put("stats", Map.of(
                "total", 0,
                "ambiguous", "SYMBOL_AMBIGUOUS".equals(failure.code()),
                "candidates", candidates.size(),
                "reason", failure.code().toLowerCase()));
        if (!candidates.isEmpty()) {
            out.put("next_queries", failure.resolution().candidates().stream()
                    .map(node -> contextCommand(node.id, index, module, scope))
                    .toList());
        }
        System.out.println(JsonFormatter.toJson(out));
        return 2;
    }

    private static Map<String, Object> candidate(NodeRow node) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", node.id);
        out.put("symbol_id", node.symbolId);
        out.put("kind", node.kind);
        out.put("qualified_name", node.qualifiedName);
        out.put("module", node.module);
        out.put("scope", node.scope);
        return out;
    }

    private static String contextCommand(String id, Path index,
                                         String module, String scope) {
        List<String> args = new ArrayList<>(List.of("anatomist", "context", id));
        Disclosure.addOption(args, "--module", module);
        Disclosure.addOption(args, "--scope", scope);
        Disclosure.addOption(args, "--index", index);
        return Disclosure.renderCommand(args);
    }
}
