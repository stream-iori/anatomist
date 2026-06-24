package com.anatomist.core;

import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Late-binds edges that SymbolSolver classified as external but whose rendered
 * target FQN exactly matches a node emitted by this index.
 */
public final class EdgeTargetBinder {

    private EdgeTargetBinder() {}

    public static int bindExternalTargets(ExtractionResult result) {
        Set<String> knownNodeIds = new HashSet<>();
        for (Node n : result.nodes) {
            if (n.id != null) knownNodeIds.add(n.id);
        }
        return bindExternalTargets(result, knownNodeIds);
    }

    public static int bindExternalTargets(ExtractionResult result, Set<String> knownNodeIds) {
        if (result == null) return 0;
        Set<String> known = new HashSet<>();
        if (knownNodeIds != null) known.addAll(knownNodeIds);
        for (Node n : result.nodes) {
            if (n.id != null) known.add(n.id);
        }
        Map<String, String> uniqueByArity = uniqueMethodTargetsByArity(known);
        if (known.isEmpty()) return 0;
        int changed = 0;
        for (Edge e : result.edges) {
            if (!e.isExternal || e.externalTargetFqn == null) continue;
            String target = known.contains(e.externalTargetFqn)
                    ? e.externalTargetFqn
                    : uniqueByArity.get(methodArityKey(e.externalTargetFqn));
            if (target == null) continue;
            e.targetId = target;
            e.externalTargetFqn = null;
            e.isExternal = false;
            changed++;
        }
        return changed;
    }

    private static Map<String, String> uniqueMethodTargetsByArity(Set<String> known) {
        Map<String, String> out = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (String id : known) {
            String key = methodArityKey(id);
            if (key == null) continue;
            if (ambiguous.contains(key)) continue;
            String previous = out.putIfAbsent(key, id);
            if (previous != null && !previous.equals(id)) {
                out.remove(key);
                ambiguous.add(key);
            }
        }
        return out;
    }

    private static String methodArityKey(String methodId) {
        if (methodId == null) return null;
        int open = methodId.indexOf('(');
        int hash = methodId.lastIndexOf('#', open >= 0 ? open : methodId.length());
        int close = methodId.lastIndexOf(')');
        if (hash < 0 || open < hash || close < open) return null;
        String ownerAndName = methodId.substring(0, open);
        String params = methodId.substring(open + 1, close).trim();
        int arity = params.isEmpty() ? 0 : params.split(",", -1).length;
        return ownerAndName + "/" + arity;
    }
}
