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
        return calleesTraversal(methodRef, depth, throughCallbacks).items();
    }

    public TraversalResult<EdgeRow> calleesTraversal(String methodRef, int depth,
                                                      boolean throughCallbacks) {
        return callsFromTraversal(resolver.resolveMethodIds(methodRef), depth, throughCallbacks);
    }

    public List<EdgeRow> callersOf(String methodRef, int depth) {
        return callersOf(methodRef, depth, false);
    }

    public List<EdgeRow> callersOf(String methodRef, int depth, boolean throughCallbacks) {
        return callersTraversal(methodRef, depth, throughCallbacks).items();
    }

    public TraversalResult<EdgeRow> callersTraversal(String methodRef, int depth,
                                                      boolean throughCallbacks) {
        int effectiveDepth = effectiveDepth(depth, 1);
        TraversalResult<EdgeRow> internal = callsToTraversal(
                resolver.resolveMethodIds(methodRef), depth, throughCallbacks);
        TraversalResult<EdgeRow> external = callsToExternalTraversal(
                methodRef, depth, throughCallbacks);
        List<EdgeRow> combined = new ArrayList<>(internal.items());
        combined.addAll(external.items());
        List<EdgeRow> rows = dedup(combined);
        int reachedDepth = rows.stream().mapToInt(row -> row.depth == null ? 0 : row.depth)
                .max().orElse(0);
        return new TraversalResult<>(rows, depth, effectiveDepth, reachedDepth,
                internal.depthTruncated() || external.depthTruncated(),
                internal.frontierCount() + external.frontierCount(), false);
    }

    List<EdgeRow> callsFrom(List<String> seedIds, int depth) {
        return callsFrom(seedIds, depth, false);
    }

    List<EdgeRow> callsFrom(List<String> seedIds, int depth, boolean throughCallbacks) {
        return callsFromTraversal(seedIds, depth, throughCallbacks).items();
    }

    private TraversalResult<EdgeRow> callsFromTraversal(List<String> seedIds, int depth,
                                                         boolean throughCallbacks) {
        int depthCap = effectiveDepth(depth, 1);
        if (seedIds.isEmpty()) return emptyTraversal(depth, depthCap);

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
        List<EdgeRow> rows = dedup(result);
        int reachedDepth = rows.stream().mapToInt(row -> row.depth == null ? 0 : row.depth)
                .max().orElse(0);
        int frontierCount = outgoingFrontierCount(new ArrayList<>(frontier), visited,
                throughCallbacks, depthCap + 1);
        return new TraversalResult<>(rows, depth, depthCap, reachedDepth,
                frontierCount > 0, frontierCount, false);
    }

    List<EdgeRow> callsTo(List<String> seedIds, int depth) {
        return callsTo(seedIds, depth, false);
    }

    List<EdgeRow> callsTo(List<String> seedIds, int depth, boolean throughCallbacks) {
        return callsToTraversal(seedIds, depth, throughCallbacks).items();
    }

    private TraversalResult<EdgeRow> callsToTraversal(List<String> seedIds, int depth,
                                                       boolean throughCallbacks) {
        int depthCap = effectiveDepth(depth, 1);
        if (seedIds.isEmpty()) return emptyTraversal(depth, depthCap);

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
        List<EdgeRow> rows = dedup(result);
        int reachedDepth = rows.stream().mapToInt(row -> row.depth == null ? 0 : row.depth)
                .max().orElse(0);
        int frontierCount = incomingFrontierCount(new ArrayList<>(frontier), visited,
                throughCallbacks, depthCap + 1);
        return new TraversalResult<>(rows, depth, depthCap, reachedDepth,
                frontierCount > 0, frontierCount, false);
    }

    /**
     * Reverse-trace an external method that is represented only by
     * {@code edges.external_target_fqn}.  The external edge is the first hop;
     * later hops stay inside the project graph and reuse normal caller traversal.
     */
    private TraversalResult<EdgeRow> callsToExternalTraversal(String methodRef, int depth,
                                                               boolean throughCallbacks) {
        int effectiveDepth = effectiveDepth(depth, 1);
        ExternalMethodSelector selector = ExternalMethodSelector.parse(methodRef);
        if (selector == null) return emptyTraversal(depth, effectiveDepth);

        List<EdgeRow> result = queryExternalCallsIn(selector, 1);
        if (throughCallbacks) rewriteCallbackSources(result);
        if (result.isEmpty()) return emptyTraversal(depth, effectiveDepth);

        List<String> directCallers = result.stream()
                .map(edge -> edge.source)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (effectiveDepth == 1) {
            int frontierCount = incomingFrontierCount(directCallers,
                    new HashSet<>(directCallers), throughCallbacks, 2);
            return new TraversalResult<>(dedup(result), depth, effectiveDepth, 1,
                    frontierCount > 0, frontierCount, false);
        }
        TraversalResult<EdgeRow> upstream = callsToTraversal(
                directCallers, effectiveDepth - 1, throughCallbacks);
        List<EdgeRow> shifted = new ArrayList<>(upstream.items());
        shifted.forEach(edge -> edge.depth = edge.depth + 1);
        result.addAll(shifted);
        List<EdgeRow> rows = dedup(result);
        int reachedDepth = rows.stream().mapToInt(row -> row.depth == null ? 0 : row.depth)
                .max().orElse(1);
        return new TraversalResult<>(rows, depth, effectiveDepth, reachedDepth,
                upstream.depthTruncated(), upstream.frontierCount(), false);
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
        return callPathTraversal(fromMethodRef, toMethodRef, maxDepth, throughCallbacks).items();
    }

    public TraversalResult<EdgeRow> callPathTraversal(String fromMethodRef, String toMethodRef,
                                                       int maxDepth, boolean throughCallbacks) {
        List<String> froms = resolver.resolveMethodIds(fromMethodRef);
        List<String> tos   = resolver.resolveMethodIds(toMethodRef);
        int depthCap = effectiveDepth(maxDepth, 5);
        if (froms.isEmpty() || tos.isEmpty()) return emptyTraversal(maxDepth, depthCap);

        Map<String, String> parent = new LinkedHashMap<>();
        Map<String, EdgeRow> hopRows = new HashMap<>();
        Map<String, Integer> depth = new HashMap<>();
        Set<String> visitedCallbackBodies = new HashSet<>();
        Deque<String> bfsFrontier = new ArrayDeque<>();
        Set<String> boundary = new LinkedHashSet<>();
        for (String f : froms) { depth.put(f, 0); bfsFrontier.add(f); }

        String hit = null;
        Set<String> toSet = new HashSet<>(tos);
        while (!bfsFrontier.isEmpty() && hit == null) {
            String cur = bfsFrontier.pollFirst();
            int d = depth.get(cur);
            if (d >= depthCap) {
                boundary.add(cur);
                continue;
            }

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
        int reachedDepth = depth.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (hit == null) {
            int frontierCount = pathFrontierCount(boundary, depth.keySet(), throughCallbacks,
                    visitedCallbackBodies, depthCap + 1);
            return new TraversalResult<>(List.of(), maxDepth, depthCap, reachedDepth,
                    frontierCount > 0, frontierCount, false);
        }

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
        return new TraversalResult<>(rows, maxDepth, depthCap, rows.size(),
                false, 0, false);
    }

    private int outgoingFrontierCount(List<String> frontier,
                                      Set<String> visited,
                                      boolean throughCallbacks,
                                      int depth) {
        if (frontier.isEmpty()) return 0;
        Set<String> owners = new LinkedHashSet<>();
        List<EdgeRow> calls = queryCallsOut(frontier, depth);
        calls.forEach(edge -> owners.add(edge.source));
        Set<String> dispatchCandidates = new LinkedHashSet<>(frontier);
        calls.stream().map(edge -> edge.target).filter(Objects::nonNull)
                .forEach(dispatchCandidates::add);
        if (throughCallbacks) {
            Map<String, List<String>> bodies = callbackBodies.collect(frontier, new HashSet<>(visited));
            for (Map.Entry<String, List<String>> entry : bodies.entrySet()) {
                if (!entry.getValue().isEmpty()
                        && !queryCallsOut(entry.getValue(), depth).isEmpty()) {
                    owners.add(entry.getKey());
                }
            }
        }
        batchOverrideImpls(dispatchCandidates).forEach((source, targets) -> {
            if (!targets.isEmpty()) owners.add(source);
        });
        return owners.size();
    }

    private int incomingFrontierCount(List<String> frontier,
                                      Set<String> visited,
                                      boolean throughCallbacks,
                                      int depth) {
        if (frontier.isEmpty()) return 0;
        Set<String> owners = new LinkedHashSet<>();
        List<EdgeRow> calls = queryCallsIn(frontier, depth);
        if (throughCallbacks) rewriteCallbackSources(calls);
        calls.stream().map(edge -> edge.source).filter(Objects::nonNull).forEach(owners::add);
        Map<String, List<String>> interfaces = batchOverriddenIface(frontier);
        List<String> bridged = interfaces.values().stream().flatMap(Collection::stream)
                .filter(id -> !visited.contains(id)).distinct().toList();
        owners.addAll(bridged);
        if (!bridged.isEmpty()) {
            List<EdgeRow> bridgedCalls = queryCallsIn(bridged, depth);
            if (throughCallbacks) rewriteCallbackSources(bridgedCalls);
            bridgedCalls.stream().map(edge -> edge.source).filter(Objects::nonNull)
                    .forEach(owners::add);
        }
        return owners.size();
    }

    private int pathFrontierCount(Collection<String> boundary,
                                  Set<String> visited,
                                  boolean throughCallbacks,
                                  Set<String> visitedCallbackBodies,
                                  int depth) {
        int count = 0;
        for (String node : boundary) {
            boolean hidden = pathOutgoingCalls(node, depth, throughCallbacks,
                    new HashSet<>(visitedCallbackBodies)).stream()
                    .map(edge -> edge.target)
                    .anyMatch(target -> target != null && !visited.contains(target));
            if (!hidden) {
                hidden = overrideImpls(node).stream().anyMatch(target -> !visited.contains(target));
            }
            if (hidden) count++;
        }
        return count;
    }

    private static int effectiveDepth(int requested, int defaultDepth) {
        int positive = requested > 0 ? requested : defaultDepth;
        return Math.min(Math.max(1, positive), MAX_DEPTH);
    }

    private static <T> TraversalResult<T> emptyTraversal(int requestedDepth, int effectiveDepth) {
        return new TraversalResult<>(List.of(), requestedDepth, effectiveDepth,
                0, false, 0, false);
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
