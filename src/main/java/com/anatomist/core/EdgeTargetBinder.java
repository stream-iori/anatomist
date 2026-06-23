package com.anatomist.core;

import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;

import java.util.HashSet;
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
        if (known.isEmpty()) return 0;
        int changed = 0;
        for (Edge e : result.edges) {
            if (!e.isExternal || e.externalTargetFqn == null) continue;
            if (!known.contains(e.externalTargetFqn)) continue;
            e.targetId = e.externalTargetFqn;
            e.externalTargetFqn = null;
            e.isExternal = false;
            changed++;
        }
        return changed;
    }
}
