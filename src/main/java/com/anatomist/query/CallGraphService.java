package com.anatomist.query;

import com.anatomist.model.GraphConstants;

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
    private final CallbackBodyCollector callbackBodies;

    public CallGraphService(Connection conn, NodeResolver resolver) {
        this.conn = conn;
        this.resolver = resolver;
        this.callbackBodies = new CallbackBodyCollector(conn);
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
        int depthCap = Math.min(Math.max(1, depth), MAX_DEPTH);
        List<EdgeRow> result = new ArrayList<>(
                callsTo(resolver.resolveMethodIds(methodRef), depthCap, throughCallbacks));
        result.addAll(callsToExternal(methodRef, depthCap, throughCallbacks));
        return dedup(result);
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
                // frontier method, attributing them to the outer method.
                Map<String, List<String>> bodiesByMethod = callbackBodies.collect(current, visited);
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
     * Reverse-trace an external method that is represented only by
     * {@code edges.external_target_fqn}.  The external edge is the first hop;
     * later hops stay inside the project graph and reuse normal caller traversal.
     */
    private List<EdgeRow> callsToExternal(String methodRef, int depth, boolean throughCallbacks) {
        ExternalMethodSelector selector = ExternalMethodSelector.parse(methodRef);
        if (selector == null) return Collections.emptyList();

        List<EdgeRow> result = queryExternalCallsIn(selector, 1);
        if (throughCallbacks) rewriteCallbackSources(result);
        if (depth <= 1 || result.isEmpty()) return result;

        List<String> directCallers = result.stream()
                .map(edge -> edge.source)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<EdgeRow> upstream = callsTo(directCallers, depth - 1, throughCallbacks);
        upstream.forEach(edge -> edge.depth = edge.depth + 1);
        result.addAll(upstream);
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
        return callPath(fromMethodRef, toMethodRef, maxDepth, false);
    }

    public List<EdgeRow> callPath(String fromMethodRef, String toMethodRef,
                                  int maxDepth, boolean throughCallbacks) {
        List<String> froms = resolver.resolveMethodIds(fromMethodRef);
        List<String> tos   = resolver.resolveMethodIds(toMethodRef);
        if (froms.isEmpty() || tos.isEmpty()) return Collections.emptyList();
        int depthCap = Math.min(maxDepth > 0 ? maxDepth : 5, MAX_DEPTH);

        Map<String, String> parent = new LinkedHashMap<>();
        Map<String, EdgeRow> hopRows = new HashMap<>();
        Map<String, Integer> depth = new HashMap<>();
        Set<String> visitedCallbackBodies = new HashSet<>();
        Deque<String> bfsFrontier = new ArrayDeque<>();
        for (String f : froms) { depth.put(f, 0); bfsFrontier.add(f); }

        String hit = null;
        Set<String> toSet = new HashSet<>(tos);
        while (!bfsFrontier.isEmpty() && hit == null) {
            String cur = bfsFrontier.pollFirst();
            int d = depth.get(cur);
            if (d >= depthCap) continue;

            for (EdgeRow edge : pathOutgoingCalls(cur, d + 1, throughCallbacks, visitedCallbackBodies)) {
                String next = edge.target;
                if (next == null || Boolean.TRUE.equals(edge.isExternal) || depth.containsKey(next)) continue;
                depth.put(next, d + 1);
                parent.put(next, cur);
                hopRows.put(next, edge);
                if (toSet.contains(next)) { hit = next; break; }
                bfsFrontier.addLast(next);
            }

            if (hit == null) {
                for (String impl : overrideImpls(cur)) {
                    if (depth.containsKey(impl)) continue;
                    depth.put(impl, d + 1);
                    parent.put(impl, cur);
                    hopRows.put(impl, makeOverrideEdge(cur, impl, d + 1));
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
            String tgt = chain.get(i);
            EdgeRow row = hopRows.get(tgt);
            if (row != null) {
                row.depth = i;
                rows.add(row);
            }
        }
        return rows;
    }

    private List<EdgeRow> pathOutgoingCalls(String methodId, int depth,
                                            boolean throughCallbacks,
                                            Set<String> visitedCallbackBodies) {
        List<EdgeRow> rows = queryCallsOut(List.of(methodId), depth).stream()
                .filter(e -> !Boolean.TRUE.equals(e.isExternal) && e.target != null)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (!throughCallbacks) return rows;

        Map<String, List<String>> bodiesByMethod =
                callbackBodies.collect(List.of(methodId), visitedCallbackBodies);
        List<String> bodyIds = bodiesByMethod.getOrDefault(methodId, Collections.emptyList());
        if (bodyIds.isEmpty()) return rows;

        NodeRow outerNode = resolver.readNodeById(methodId);
        for (EdgeRow edge : queryCallsOut(bodyIds, depth)) {
            if (Boolean.TRUE.equals(edge.isExternal) || edge.target == null) continue;
            edge.via = edge.source;
            edge.source = methodId;
            if (outerNode != null) {
                edge.sourceLabel = outerNode.label;
                edge.sourceFile = outerNode.sourceFile;
            }
            if (edge.callKind == null) edge.callKind = "CALLBACK";
            rows.add(edge);
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
                    + " WHERE e.source_id IN (" + ph + ") AND e.relation = '" + GraphConstants.Relation.CALLS + "'"
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
                    + " WHERE e.target_id IN (" + ph + ") AND e.relation = '" + GraphConstants.Relation.CALLS + "'"
                    + " AND e.is_external = 0"
                    + " ORDER BY e.source_id, e.target_id";
            List<Object> args = new ArrayList<>();
            args.add(d);
            args.addAll(batch);
            all.addAll(runEdgeQuery(conn, sql, args));
        }
        return all;
    }

    private List<EdgeRow> queryExternalCallsIn(ExternalMethodSelector selector, int d) {
        String predicate = selector.exact ? "e.external_target_fqn = ?"
                : "e.external_target_fqn LIKE ? ESCAPE '\\'";
        String sql = "SELECT " + RowMappers.edgeColsFlat("?")
                + RowMappers.EDGE_FROM_JOINS
                + " WHERE e.relation = '" + GraphConstants.Relation.CALLS + "'"
                + " AND e.is_external = 1 AND " + predicate
                + " ORDER BY e.source_id, e.external_target_fqn";
        return runEdgeQuery(conn, sql, List.of(d, selector.sqlValue()));
    }

    private static final class ExternalMethodSelector {
        final String value;
        final boolean exact;

        private ExternalMethodSelector(String value, boolean exact) {
            this.value = value;
            this.exact = exact;
        }

        static ExternalMethodSelector parse(String methodRef) {
            if (methodRef == null || methodRef.isBlank()) return null;
            int hash = methodRef.indexOf('#');
            if (hash <= 0 || hash == methodRef.length() - 1) return null;
            return new ExternalMethodSelector(methodRef, methodRef.indexOf('(', hash) >= 0);
        }

        String sqlValue() {
            if (exact) return value;
            return likePrefix(value + "(");
        }

        private static String likePrefix(String value) {
            return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        }
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
                  + " WHERE target_id IN (" + ph + ") AND relation = '" + GraphConstants.Relation.OVERRIDES + "' AND is_external = 0")) {
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
                  + " WHERE source_id IN (" + ph + ") AND relation = '" + GraphConstants.Relation.OVERRIDES + "' AND is_external = 0")) {
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
              + " WHERE target_id = ? AND relation = '" + GraphConstants.Relation.OVERRIDES + "' AND is_external = 0")) {
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
              + " WHERE source_id = ? AND relation = '" + GraphConstants.Relation.OVERRIDES + "' AND is_external = 0")) {
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
        row.relation = GraphConstants.Relation.OVERRIDES;
        row.confidence = GraphConstants.Confidence.INFERRED;
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
                    + "@" + r.depth
                    + "@" + r.relation
                    + "@" + r.sourceLocation
                    + (GraphConstants.CallKind.REFLECTION.equals(r.callKind)
                    ? ":" + GraphConstants.CallKind.REFLECTION : "");
            if (!seen.add(key)) it.remove();
        }
        return rows;
    }
}
