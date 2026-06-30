package com.anatomist.core;

import com.anatomist.json.Json;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adds DI-informed edges after all extractors have contributed raw graph data.
 * The original interface CALLS are preserved; these extra edges make the
 * configured narrowing visible without pretending it is always a runtime fact.
 */
public final class WiringResolver {

    public int apply(ExtractionResult result) {
        if (result == null || result.edges == null || result.edges.isEmpty()) return 0;
        List<Edge> additions = resolve(result.edges);
        result.edges.addAll(additions);
        return additions.size();
    }

    public List<Edge> resolve(List<Edge> edges) {
        if (edges == null || edges.isEmpty()) return List.of();

        Map<String, List<String>> implTypesByInterface = new HashMap<>();
        Map<String, List<String>> implMethodsByInterfaceMethod = new HashMap<>();
        for (Edge e : edges) {
            if (isGenerated(e)) continue;
            if (e.isExternal || e.targetId == null || e.sourceId == null) continue;
            if (GraphConstants.Relation.IMPLEMENTS.equals(e.relation)) {
                implTypesByInterface.computeIfAbsent(e.targetId, k -> new ArrayList<>()).add(e.sourceId);
            } else if (GraphConstants.Relation.OVERRIDES.equals(e.relation)) {
                implMethodsByInterfaceMethod.computeIfAbsent(e.targetId, k -> new ArrayList<>()).add(e.sourceId);
            }
        }

        List<Edge> additions = new ArrayList<>();
        Set<String> seen = existingEdgeKeys(edges);

        List<Edge> injections = edges.stream()
                .filter(e -> !isGenerated(e)
                        && GraphConstants.Relation.INJECTS.equals(e.relation) && !e.isExternal
                        && e.sourceId != null && e.targetId != null)
                .toList();

        for (Edge inject : injections) {
            List<String> implTypes = distinct(implTypesByInterface.get(inject.targetId));
            if (!implTypes.isEmpty()) {
                addWires(inject, implTypes, additions, seen);
            }
        }

        for (Edge call : edges) {
            if (isGenerated(call)) continue;
            if (!GraphConstants.Relation.CALLS.equals(call.relation) || call.isExternal || call.targetId == null) continue;
            String callerType = ownerTypeOfMethod(call.sourceId);
            String calleeType = ownerTypeOfMethod(call.targetId);
            if (callerType == null || calleeType == null) continue;
            boolean callerInjectsCalleeType = injections.stream()
                    .anyMatch(i -> callerType.equals(i.sourceId) && calleeType.equals(i.targetId));
            if (!callerInjectsCalleeType) continue;

            List<String> implMethods = distinct(implMethodsByInterfaceMethod.get(call.targetId));
            if (implMethods.isEmpty()) continue;
            addWiredCalls(call, calleeType, implMethods, additions, seen);
        }

        return additions;
    }

    private void addWires(Edge inject, List<String> implTypes, List<Edge> additions, Set<String> seen) {
        String confidence = implTypes.size() == 1
                ? GraphConstants.Confidence.INFERRED
                : GraphConstants.Confidence.AMBIGUOUS;
        String metadata = metadata(GraphConstants.MetadataVia.INJECTION, inject.targetId, implTypes);
        for (String impl : implTypes) {
            Edge e = new Edge();
            e.sourceId = inject.sourceId;
            e.targetId = impl;
            e.relation = GraphConstants.Relation.WIRES;
            e.confidence = confidence;
            e.isExternal = false;
            e.sourceFile = inject.sourceFile;
            e.sourceLocation = inject.sourceLocation;
            e.metadata = metadata;
            addIfNew(e, additions, seen);
        }
    }

    private void addWiredCalls(Edge call, String interfaceType, List<String> implMethods,
                               List<Edge> additions, Set<String> seen) {
        String confidence = implMethods.size() == 1
                ? GraphConstants.Confidence.INFERRED
                : GraphConstants.Confidence.AMBIGUOUS;
        String metadata = metadata(GraphConstants.MetadataVia.INJECTED_CALL, interfaceType, call.targetId, implMethods);
        for (String implMethod : implMethods) {
            Edge e = new Edge();
            e.sourceId = call.sourceId;
            e.targetId = implMethod;
            e.relation = GraphConstants.Relation.CALLS;
            e.callKind = call.callKind;
            e.confidence = confidence;
            e.context = call.context;
            e.isExternal = false;
            e.sourceFile = call.sourceFile;
            e.sourceLocation = call.sourceLocation;
            e.metadata = metadata;
            addIfNew(e, additions, seen);
        }
    }

    private static String metadata(String via, String source, List<String> candidates) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("via", via);
        m.put("source", source);
        m.put("candidates", candidates);
        return Json.writeCompact(m);
    }

    private static String metadata(String via, String interfaceType, String source, List<String> candidates) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("via", via);
        m.put("interfaceType", interfaceType);
        m.put("source", source);
        m.put("candidates", candidates);
        return Json.writeCompact(m);
    }

    private static List<String> distinct(List<String> in) {
        if (in == null || in.isEmpty()) return List.of();
        return new ArrayList<>(new LinkedHashSet<>(in));
    }

    private static void addIfNew(Edge e, List<Edge> additions, Set<String> seen) {
        String key = key(e);
        if (!seen.add(key)) return;
        additions.add(e);
    }

    private static Set<String> existingEdgeKeys(List<Edge> edges) {
        Set<String> out = new HashSet<>();
        for (Edge e : edges) out.add(key(e));
        return out;
    }

    public static boolean isGenerated(Edge edge) {
        return edge != null && isGeneratedMetadata(edge.metadata);
    }

    public static boolean isGeneratedMetadata(String metadata) {
        return metadata != null
                && (metadata.contains("\"via\":\"" + GraphConstants.MetadataVia.INJECTION + "\"")
                || metadata.contains("\"via\":\"" + GraphConstants.MetadataVia.INJECTED_CALL + "\""));
    }

    private static String key(Edge e) {
        return e.relation + "|" + e.sourceId + "|" + e.targetId + "|"
                + e.externalTargetFqn + "|" + e.callKind + "|" + e.sourceLocation;
    }

    private static String ownerTypeOfMethod(String methodId) {
        if (methodId == null) return null;
        int hash = methodId.indexOf('#');
        if (hash <= 0) return null;
        return methodId.substring(0, hash);
    }
}
