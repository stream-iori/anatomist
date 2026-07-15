package com.anatomist.incremental;

import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Symbol-level contract delta used to select unchanged source files for realignment. */
final class SymbolGraphDelta {

    private SymbolGraphDelta() {}

    static Impact analyze(Map<String, Node> oldNodes, List<Node> newNodes) {
        Map<String, Node> prior = oldNodes == null ? Map.of() : oldNodes;
        Map<String, Node> next = new HashMap<>();
        if (newNodes != null) {
            for (Node node : newNodes) next.put(node.id, node);
        }

        Set<String> removed = new LinkedHashSet<>(prior.keySet());
        removed.removeAll(next.keySet());
        Set<String> added = new LinkedHashSet<>(next.keySet());
        added.removeAll(prior.keySet());
        Set<String> contractChanged = new LinkedHashSet<>();
        for (String id : prior.keySet()) {
            Node after = next.get(id);
            if (after != null && !sameContract(prior.get(id), after)) contractChanged.add(id);
        }

        Set<String> exactTargets = new LinkedHashSet<>(removed);
        exactTargets.addAll(contractChanged);
        Set<String> ownerTargets = new LinkedHashSet<>();
        Set<String> implementorTargets = new LinkedHashSet<>();
        Set<String> externalPrefixes = new LinkedHashSet<>();
        Set<String> externalExactTargets = new LinkedHashSet<>();

        for (String id : added) addExternalTarget(
                next.get(id), externalExactTargets, externalPrefixes);
        for (String id : contractChanged) {
            Node before = prior.get(id);
            Node after = next.get(id);
            addExternalTarget(after, externalExactTargets, externalPrefixes);
            if (isDeclaredType(before) || isDeclaredType(after)) ownerTargets.add(id);
        }

        Map<String, Set<String>> priorMethodsByFamily = methodsByFamily(prior.values());
        Set<String> changedMemberIds = new LinkedHashSet<>(added);
        changedMemberIds.addAll(removed);
        for (String id : changedMemberIds) {
            Node node = next.containsKey(id) ? next.get(id) : prior.get(id);
            if (!isMethod(node)) continue;
            Set<String> siblings = priorMethodsByFamily.getOrDefault(methodFamily(node), Set.of());
            if (!siblings.isEmpty()) exactTargets.addAll(siblings);
            String ownerId = ownerStorageId(node);
            Node owner = next.get(ownerId);
            if (owner == null) owner = prior.get(ownerId);
            if (owner != null && GraphConstants.Kind.INTERFACE.equals(owner.kind)) {
                implementorTargets.add(ownerId);
            }
        }

        externalPrefixes.remove(null);
        externalExactTargets.remove(null);
        return new Impact(exactTargets, ownerTargets, implementorTargets,
                externalPrefixes, externalExactTargets,
                removed, added, contractChanged);
    }

    private static boolean sameContract(Node before, Node after) {
        return Objects.equals(before.kind, after.kind)
                && Objects.equals(before.qualifiedName, after.qualifiedName)
                && Objects.equals(before.pkg, after.pkg)
                && Objects.equals(before.module, after.module)
                && Objects.equals(before.scope, after.scope)
                && Objects.equals(before.metadata, after.metadata);
    }

    private static Map<String, Set<String>> methodsByFamily(Iterable<Node> nodes) {
        Map<String, Set<String>> out = new HashMap<>();
        for (Node node : nodes) {
            if (!isMethod(node)) continue;
            out.computeIfAbsent(methodFamily(node), ignored -> new HashSet<>()).add(node.id);
        }
        return out;
    }

    private static String methodFamily(Node node) {
        return node.module + "\u0000" + node.scope + "\u0000" + node.qualifiedName;
    }

    private static boolean isMethod(Node node) {
        return node != null && GraphConstants.METHOD_KINDS.contains(node.kind);
    }

    private static boolean isDeclaredType(Node node) {
        return node != null && GraphConstants.DECLARED_TYPE_KINDS.contains(node.kind);
    }

    private static void addExternalTarget(Node node,
                                          Set<String> exactTargets,
                                          Set<String> prefixes) {
        if (node == null) return;
        if (isMethod(node)) {
            prefixes.add(node.qualifiedName + "(");
        } else {
            exactTargets.add(node.qualifiedName);
            if (isDeclaredType(node)) prefixes.add(node.qualifiedName + "#");
        }
    }

    private static String ownerStorageId(Node member) {
        if (member == null || member.id == null) return null;
        int hash = member.id.indexOf('#');
        return hash < 0 ? member.id : member.id.substring(0, hash);
    }

    record Impact(Set<String> exactTargetIds,
                  Set<String> ownerTargetIds,
                  Set<String> implementorTargetIds,
                  Set<String> externalPrefixes,
                  Set<String> externalExactTargets,
                  Set<String> removedIds,
                  Set<String> addedIds,
                  Set<String> contractChangedIds) {
        boolean isEmpty() {
            return exactTargetIds.isEmpty() && ownerTargetIds.isEmpty()
                    && implementorTargetIds.isEmpty() && externalPrefixes.isEmpty()
                    && externalExactTargets.isEmpty();
        }
    }
}
