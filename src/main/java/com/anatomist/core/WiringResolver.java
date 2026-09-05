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
 * Adds DI binding edges after all extractors have contributed raw graph data.
 * Runtime call candidates are derived by the dispatch query, never persisted as CALLS.
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
        for (Edge e : edges) {
            if (isGenerated(e)) continue;
            if (e.isExternal || e.targetId == null || e.sourceId == null) continue;
            if (GraphConstants.Relation.IMPLEMENTS.equals(e.relation)) {
                implTypesByInterface.computeIfAbsent(e.targetId, k -> new ArrayList<>()).add(e.sourceId);
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

    private static String metadata(String via, String source, List<String> candidates) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("via", via);
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
