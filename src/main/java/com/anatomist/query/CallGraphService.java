package com.anatomist.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static com.anatomist.query.QueryInfra.*;

public class CallGraphService {

    public static final int MAX_DEPTH = 20;

    private final Connection conn;
    private final NodeResolver resolver;

    public CallGraphService(Connection conn, NodeResolver resolver) {
        this.conn = conn;
        this.resolver = resolver;
    }

    public List<EdgeRow> calleesOf(String methodRef, int depth) {
        return calleesOf(methodRef, depth, false);
    }

    public List<EdgeRow> calleesOf(String methodRef, int depth, boolean throughCallbacks) {
        return callsFrom(resolver.resolveMethodIds(methodRef), Math.max(1, depth), throughCallbacks);
    }

    public List<EdgeRow> callersOf(String methodRef, int depth) {
        return callersOf(methodRef, depth, false);
    }

    public List<EdgeRow> callersOf(String methodRef, int depth, boolean throughCallbacks) {
        return callsTo(resolver.resolveMethodIds(methodRef), Math.max(1, depth), throughCallbacks);
    }

    List<EdgeRow> callsFrom(List<String> seedIds, int depth) {
        return callsFrom(seedIds, depth, false);
    }

    List<EdgeRow> callsFrom(List<String> seedIds, int depth, boolean throughCallbacks) {
        if (seedIds.isEmpty()) return Collections.emptyList();
        int depthCap = Math.min(depth, MAX_DEPTH);

        List<EdgeRow> result = new ArrayList<>();
        Set<String> visited = new HashSet<>(seedIds);
        Deque<String> frontier = new ArrayDeque<>(seedIds);

        for (int d = 1; d <= depthCap && !frontier.isEmpty(); d++) {
            List<String> current = new ArrayList<>(frontier);
            frontier.clear();

            List<EdgeRow> callEdges = queryCallsOut(current, d);

            if (throughCallbacks) {
                // Follow CALLS inside anonymous-class / lambda bodies defined within each
                // frontier method, attributing them to the outer method (see collectCallbackBodies).
                Map<String, List<String>> bodiesByMethod = collectCallbackBodies(current, visited);
                for (Map.Entry<String, List<String>> entry : bodiesByMethod.entrySet()) {
                    String outer = entry.getKey();
                    List<String> bodyIds = entry.getValue();
                    if (bodyIds.isEmpty()) continue;
                    NodeRow outerNode = resolver.readNodeById(outer);
                    for (EdgeRow be : queryCallsOut(bodyIds, d)) {
                        be.via = be.source;
                        be.source = outer;
                        if (outerNode != null) {
                            be.sourceLabel = outerNode.label;
                            be.sourceFile = outerNode.sourceFile;
                        }
                        if (be.callKind == null) be.callKind = "CALLBACK";
                        callEdges.add(be);
                    }
                }
            }

            result.addAll(callEdges);

            Set<String> dispatchCandidates = new HashSet<>(current);
            for (EdgeRow e : callEdges) {
                if (!e.isExternal && e.target != null) {
                    if (visited.add(e.target)) frontier.addLast(e.target);
                    dispatchCandidates.add(e.target);
                }
            }

            Map<String, List<String>> overrides = batchOverrideImpls(dispatchCandidates);
            Set<String> nodeIdsToPreload = new HashSet<>();
            for (Map.Entry<String, List<String>> entry : overrides.entrySet()) {
                nodeIdsToPreload.add(entry.getKey());
                nodeIdsToPreload.addAll(entry.getValue());
            }
            resolver.preloadNodes(nodeIdsToPreload);
            for (Map.Entry<String, List<String>> entry : overrides.entrySet()) {
                for (String impl : entry.getValue()) {
                    result.add(makeOverrideEdge(entry.getKey(), impl, d));
                    if (visited.add(impl)) frontier.addLast(impl);
                }
            }
        }
        return dedup(result);
    }

    /**
     * For each method id in {@code methods}, returns the transitive set of contained
     * anonymous-class / lambda / method-ref body node ids whose outgoing CALLS should be
     * attributed to that method. Walks CONTAINS edges; collects METHOD nodes whose id carries
     * a synthetic {@code $anon@} / {@code $lambda@} marker plus LAMBDA / METHOD_REF nodes, and
     * recurses through ANONYMOUS_CLASS containers (and nested anon/lambda) without collecting
     * the container itself. Ordinary nested types and regular member methods are ignored so a
     * class's other real methods never leak into the chain. Discovered body ids are added to
     * {@code visited} so each body is expanded at most once across depth levels.
     */
    private Map<String, List<String>> collectCallbackBodies(List<String> methods, Set<String> visited) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String m : methods) {
            List<String> bodies = new ArrayList<>();
            Set<String> localSeen = new HashSet<>();
            Deque<String> stack = new ArrayDeque<>();
            stack.push(m);
            while (!stack.isEmpty()) {
                String cur = stack.pop();
                for (NodeRef child : containsChildren(cur)) {
                    if (!localSeen.add(child.id)) continue;
                    switch (child.kind) {
                        case "LAMBDA", "METHOD_REF" -> {
                            if (visited.add(child.id)) bodies.add(child.id);
                            stack.push(child.id);
                        }
                        case "ANONYMOUS_CLASS" -> stack.push(child.id);
                        case "METHOD" -> {
                            if (child.id.contains("$anon@") || child.id.contains("$lambda@")) {
                                if (visited.add(child.id)) bodies.add(child.id);
                                stack.push(child.id);
                            }
                        }
                        default -> { /* ordinary nested type / member: ignore */ }
                    }
                }
            }
            out.put(m, bodies);
        }
        return out;
    }

    private record NodeRef(String id, String kind) {}

    /** Direct CONTAINS children (id + kind) of the given node ids, batched. */
    private List<NodeRef> containsChildren(String parentId) {
        List<NodeRef> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT n.id, n.kind FROM edges e JOIN nodes n ON n.id = e.target_id "
              + " WHERE e.source_id = ? AND e.relation = 'CONTAINS'")) {
            ps.setString(1, parentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new NodeRef(rs.getString(1), rs.getString(2)));
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }
        return out;
    }

    List<EdgeRow> callsTo(List<String> seedIds, int depth) {
        return callsTo(seedIds, depth, false);
    }

    List<EdgeRow> callsTo(List<String> seedIds, int depth, boolean throughCallbacks) {
        if (seedIds.isEmpty()) return Collections.emptyList();
        int depthCap = Math.min(depth, MAX_DEPTH);

        List<EdgeRow> result = new ArrayList<>();
        Set<String> visited = new HashSet<>(seedIds);
        Deque<String> frontier = new ArrayDeque<>(seedIds);

        for (int d = 1; d <= depthCap && !frontier.isEmpty(); d++) {
            List<String> current = new ArrayList<>(frontier);
            frontier.clear();

            List<EdgeRow> callEdges = queryCallsIn(current, d);
            if (throughCallbacks) rewriteCallbackSources(callEdges);
            result.addAll(callEdges);

            Map<String, List<String>> ifaceMap = batchOverriddenIface(current);
            Set<String> ifaceNodeIds = new HashSet<>();
            for (Map.Entry<String, List<String>> entry : ifaceMap.entrySet()) {
                ifaceNodeIds.add(entry.getKey());
                ifaceNodeIds.addAll(entry.getValue());
            }
            resolver.preloadNodes(ifaceNodeIds);
            List<String> bridged = new ArrayList<>();
            for (String node : current) {
                for (String ifaceMethod : ifaceMap.getOrDefault(node, Collections.emptyList())) {
                    if (visited.add(ifaceMethod)) {
                        result.add(makeOverrideEdge(ifaceMethod, node, d));
                        bridged.add(ifaceMethod);
                    }
                }
            }
            if (!bridged.isEmpty()) {
                List<EdgeRow> bridgedCallers = queryCallsIn(bridged, d);
                if (throughCallbacks) rewriteCallbackSources(bridgedCallers);
                result.addAll(bridgedCallers);
                for (EdgeRow e : bridgedCallers) {
                    if (e.source != null && visited.add(e.source)) {
                        frontier.addLast(e.source);
                    }
                }
            }

            for (EdgeRow e : callEdges) {
                if (e.source != null && visited.add(e.source)) {
                    frontier.addLast(e.source);
                }
            }
        }
        return dedup(result);
    }

    /**
     * Reverse of the callees-of callback penetration: when an incoming call originates from
     * inside an anonymous-class / lambda body (source id carries a {@code $anon@}/{@code $lambda@}
     * marker), rewrite the edge source to the enclosing real method so impact analysis reaches
     * the actual caller rather than a synthetic callback node. The original body id is recorded
     * in {@code via}; the frontier then continues upward from the enclosing method.
     */
    private void rewriteCallbackSources(List<EdgeRow> edges) {
        for (EdgeRow e : edges) {
            String outer = enclosingMethod(e.source);
            if (outer != null && !outer.equals(e.source)) {
                e.via = e.source;
                e.source = outer;
                NodeRow n = resolver.readNodeById(outer);
                if (n != null) { e.sourceLabel = n.label; e.sourceFile = n.sourceFile; }
                if (e.callKind == null) e.callKind = "CALLBACK";
            }
        }
    }

    /** The enclosing real method id for a callback-body node id, or null if {@code id} is not
     *  inside a {@code $anon@}/{@code $lambda@} body. Truncates at the first synthetic marker,
     *  e.g. {@code M#m()$anon@L1#process()} → {@code M#m()}. */
    static String enclosingMethod(String id) {
        if (id == null) return null;
        int anon = id.indexOf("$anon@");
        int lam = id.indexOf("$lambda@");
        int cut = anon < 0 ? lam : (lam < 0 ? anon : Math.min(anon, lam));
        return cut < 0 ? null : id.substring(0, cut);
    }

    public List<EdgeRow> callPath(String fromMethodRef, String toMethodRef, int maxDepth) {
        List<String> froms = resolver.resolveMethodIds(fromMethodRef);
        List<String> tos   = resolver.resolveMethodIds(toMethodRef);
        if (froms.isEmpty() || tos.isEmpty()) return Collections.emptyList();
        int depthCap = Math.min(maxDepth > 0 ? maxDepth : 5, MAX_DEPTH);

        Map<String, String> parent = new LinkedHashMap<>();
        Map<String, String> hopRelation = new HashMap<>();
        Map<String, Integer> depth = new HashMap<>();
        Deque<String> bfsFrontier = new ArrayDeque<>();
        for (String f : froms) { depth.put(f, 0); bfsFrontier.add(f); }

        String hit = null;
        Set<String> toSet = new HashSet<>(tos);
        while (!bfsFrontier.isEmpty() && hit == null) {
            String cur = bfsFrontier.pollFirst();
            int d = depth.get(cur);
            if (d >= depthCap) continue;

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT target_id FROM edges WHERE source_id = ? AND relation = 'CALLS' "
                  + "  AND is_external = 0 AND target_id IS NOT NULL")) {
                ps.setString(1, cur);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String next = rs.getString(1);
                        if (depth.containsKey(next)) continue;
                        depth.put(next, d + 1);
                        parent.put(next, cur);
                        hopRelation.put(next, "CALLS");
                        if (toSet.contains(next)) { hit = next; break; }
                        bfsFrontier.addLast(next);
                    }
                }
            } catch (SQLException e) {
                throw rethrow(e);
            }

            if (hit == null) {
                for (String impl : overrideImpls(cur)) {
                    if (depth.containsKey(impl)) continue;
                    depth.put(impl, d + 1);
                    parent.put(impl, cur);
                    hopRelation.put(impl, "OVERRIDES");
                    if (toSet.contains(impl)) { hit = impl; break; }
                    bfsFrontier.addLast(impl);
                }
            }
        }
        if (hit == null) return Collections.emptyList();

        List<String> chain = new ArrayList<>();
        String cur = hit;
        while (cur != null) {
            chain.add(cur);
            cur = parent.get(cur);
        }
        Collections.reverse(chain);

        List<EdgeRow> rows = new ArrayList<>();
        for (int i = 1; i < chain.size(); i++) {
            String src = chain.get(i - 1);
            String tgt = chain.get(i);
            String rel = hopRelation.getOrDefault(tgt, "CALLS");
            if ("OVERRIDES".equals(rel)) {
                rows.add(makeOverrideEdge(src, tgt, i));
            } else {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT " + RowMappers.edgeColsFlat("?")
                      + RowMappers.EDGE_FROM_JOINS
                      + " WHERE e.source_id = ? AND e.target_id = ? AND e.relation = 'CALLS' "
                      + " LIMIT 1")) {
                    ps.setInt(1, i);
                    ps.setString(2, src);
                    ps.setString(3, tgt);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            rows.add(RowMappers.mapEdge(rs));
                        }
                    }
                } catch (SQLException e) {
                    throw rethrow(e);
                }
            }
        }
        return rows;
    }

    private List<EdgeRow> queryCallsOut(List<String> sources, int d) {
        List<EdgeRow> all = new ArrayList<>();
        for (int off = 0; off < sources.size(); off += BATCH_SIZE) {
            List<String> batch = sources.subList(off, Math.min(off + BATCH_SIZE, sources.size()));
            String ph = qmarks(batch.size());
            String sql = "SELECT " + RowMappers.edgeColsFlat("?")
                    + RowMappers.EDGE_FROM_JOINS
                    + " WHERE e.source_id IN (" + ph + ") AND e.relation = 'CALLS'"
                    + " ORDER BY e.source_id, e.target_id";
            List<Object> args = new ArrayList<>();
            args.add(d);
            args.addAll(batch);
            all.addAll(runEdgeQuery(conn, sql, args));
        }
        return all;
    }

    private List<EdgeRow> queryCallsIn(List<String> targets, int d) {
        List<EdgeRow> all = new ArrayList<>();
        for (int off = 0; off < targets.size(); off += BATCH_SIZE) {
            List<String> batch = targets.subList(off, Math.min(off + BATCH_SIZE, targets.size()));
            String ph = qmarks(batch.size());
            String sql = "SELECT " + RowMappers.edgeColsFlat("?")
                    + RowMappers.EDGE_FROM_JOINS
                    + " WHERE e.target_id IN (" + ph + ") AND e.relation = 'CALLS'"
                    + " AND e.is_external = 0"
                    + " ORDER BY e.source_id, e.target_id";
            List<Object> args = new ArrayList<>();
            args.add(d);
            args.addAll(batch);
            all.addAll(runEdgeQuery(conn, sql, args));
        }
        return all;
    }

    private Map<String, List<String>> batchOverrideImpls(Collection<String> methodIds) {
        Map<String, List<String>> result = new HashMap<>();
        if (methodIds == null || methodIds.isEmpty()) return result;
        List<String> ids = new ArrayList<>(methodIds);
        for (int off = 0; off < ids.size(); off += BATCH_SIZE) {
            List<String> batch = ids.subList(off, Math.min(off + BATCH_SIZE, ids.size()));
            String ph = qmarks(batch.size());
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT target_id, source_id FROM edges"
                  + " WHERE target_id IN (" + ph + ") AND relation = 'OVERRIDES' AND is_external = 0")) {
                for (int i = 0; i < batch.size(); i++) ps.setString(i + 1, batch.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                    }
                }
            } catch (SQLException e) {
                throw rethrow(e);
            }
        }
        return result;
    }

    private Map<String, List<String>> batchOverriddenIface(Collection<String> methodIds) {
        Map<String, List<String>> result = new HashMap<>();
        if (methodIds == null || methodIds.isEmpty()) return result;
        List<String> ids = new ArrayList<>(methodIds);
        for (int off = 0; off < ids.size(); off += BATCH_SIZE) {
            List<String> batch = ids.subList(off, Math.min(off + BATCH_SIZE, ids.size()));
            String ph = qmarks(batch.size());
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT source_id, target_id FROM edges"
                  + " WHERE source_id IN (" + ph + ") AND relation = 'OVERRIDES' AND is_external = 0")) {
                for (int i = 0; i < batch.size(); i++) ps.setString(i + 1, batch.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                    }
                }
            } catch (SQLException e) {
                throw rethrow(e);
            }
        }
        return result;
    }

    private List<String> overrideImpls(String methodId) {
        List<String> impls = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT source_id FROM edges"
              + " WHERE target_id = ? AND relation = 'OVERRIDES' AND is_external = 0")) {
            ps.setString(1, methodId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) impls.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }
        return impls;
    }

    private List<String> overriddenIface(String methodId) {
        List<String> ifaces = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT target_id FROM edges"
              + " WHERE source_id = ? AND relation = 'OVERRIDES' AND is_external = 0")) {
            ps.setString(1, methodId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ifaces.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }
        return ifaces;
    }

    EdgeRow makeOverrideEdge(String ifaceMethodId, String implMethodId, int d) {
        EdgeRow row = new EdgeRow();
        row.source = ifaceMethodId;
        row.target = implMethodId;
        row.relation = "OVERRIDES";
        row.confidence = "INFERRED";
        row.isExternal = false;
        row.depth = d;
        NodeRow src = resolver.readNodeById(ifaceMethodId);
        NodeRow tgt = resolver.readNodeById(implMethodId);
        if (src != null) {
            row.sourceLabel = src.label;
            row.sourceFile = src.sourceFile;
        }
        if (tgt != null) {
            row.targetLabel = tgt.label;
            row.targetQualifiedName = tgt.qualifiedName;
        }
        return row;
    }

    private static List<EdgeRow> dedup(List<EdgeRow> rows) {
        Set<String> seen = new HashSet<>();
        for (Iterator<EdgeRow> it = rows.iterator(); it.hasNext();) {
            EdgeRow r = it.next();
            String key = r.source + "→" + (r.target != null ? r.target : r.externalTargetFqn)
                    + "@" + r.depth;
            if (!seen.add(key)) it.remove();
        }
        return rows;
    }
}
