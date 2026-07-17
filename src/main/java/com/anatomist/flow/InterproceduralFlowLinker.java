package com.anatomist.flow;

import com.anatomist.json.Json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Links call sites to callee parameters/returns/exceptions after all files are analyzed. */
public final class InterproceduralFlowLinker {

    private InterproceduralFlowLinker() {}

    public static void link(FlowResult result) {
        Map<String, FlowNode> nodes = new LinkedHashMap<>();
        Map<String, Map<String, String>> parameters = new HashMap<>();
        Map<String, List<String>> returns = new HashMap<>();
        Map<String, List<String>> exceptions = new HashMap<>();
        for (FlowNode node : result.nodes) {
            nodes.put(node.id(), node);
            if ("PARAMETER".equals(node.kind())) {
                String slot = metadataString(node.metadata(), "slot");
                if (slot != null) {
                    parameters.computeIfAbsent(node.methodId(), ignored -> new HashMap<>())
                            .put(slot, node.id());
                }
            } else if ("RETURN".equals(node.kind())) {
                returns.computeIfAbsent(node.methodId(), ignored -> new ArrayList<>()).add(node.id());
            } else if ("EXCEPTION".equals(node.kind()) || "THROW".equals(node.kind())) {
                exceptions.computeIfAbsent(node.methodId(), ignored -> new ArrayList<>()).add(node.id());
            }
        }
        Map<String, List<FlowEdge>> incoming = new HashMap<>();
        for (FlowEdge edge : result.edges) {
            incoming.computeIfAbsent(edge.targetNode(), ignored -> new ArrayList<>()).add(edge);
        }
        Set<String> dedupe = new HashSet<>();
        List<FlowEdge> linked = new ArrayList<>();
        for (FlowNode call : result.nodes) {
            if (!"CALL_RESULT".equals(call.kind()) && !"TAINT_SOURCE".equals(call.kind())
                    && !"TAINT_SINK".equals(call.kind()) && !"SANITIZER".equals(call.kind())) continue;
            String callee = metadataString(call.metadata(), "callee_method");
            if (callee == null) continue;
            for (FlowEdge argument : incoming.getOrDefault(call.id(), List.of())) {
                if (!"ARGUMENT_FLOW".equals(argument.relation())) continue;
                String parameter = parameters.getOrDefault(callee, Map.of()).get(argument.context());
                if (parameter != null) {
                    add(linked, dedupe, new FlowEdge(argument.sourceNode(), parameter,
                            "CALL_ARGUMENT", call.methodId(), call.sourceFile(),
                            "INFERRED", argument.context(), null));
                }
            }
            for (String returned : returns.getOrDefault(callee, List.of())) {
                add(linked, dedupe, new FlowEdge(returned, call.id(), "CALL_RETURN",
                        call.methodId(), call.sourceFile(), "INFERRED", null, null));
            }
            for (String thrown : exceptions.getOrDefault(callee, List.of())) {
                add(linked, dedupe, new FlowEdge(thrown, call.id(), "EXCEPTION_FLOW",
                        call.methodId(), call.sourceFile(), "POSSIBLE", "callee", null));
            }
        }
        result.edges.addAll(linked);
    }

    private static void add(List<FlowEdge> out, Set<String> seen, FlowEdge edge) {
        String key = edge.sourceNode() + "|" + edge.targetNode() + "|" + edge.relation()
                + "|" + edge.context();
        if (seen.add(key)) out.add(edge);
    }

    private static String metadataString(String json, String key) {
        if (json == null || json.isBlank()) return null;
        try {
            Object parsed = Json.parseTree(json);
            if (parsed instanceof Map<?, ?> map && map.get(key) != null) {
                return String.valueOf(map.get(key));
            }
        } catch (RuntimeException ignore) {
            // Invalid metadata is ignored; structural flow remains queryable.
        }
        return null;
    }
}
