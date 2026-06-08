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
        return callsFrom(resolver.resolveMethodIds(methodRef), Math.max(1, depth));
    }

    public List<EdgeRow> callersOf(String methodRef, int depth) {
        return callsTo(resolver.resolveMethodIds(methodRef), Math.max(1, depth));
    }

    List<EdgeRow> callsFrom(List<String> seedIds, int depth) {
        if (seedIds.isEmpty()) return Collections.emptyList();
        int depthCap = Math.min(depth, MAX_DEPTH);

        List<EdgeRow> result = new ArrayList<>();
        Set<String> visited = new HashSet<>(seedIds);
        Deque<String> frontier = new ArrayDeque<>(seedIds);

        for (int d = 1; d <= depthCap && !frontier.isEmpty(); d++) {
            List<String> current = new ArrayList<>(frontier);
            frontier.clear();

            List<EdgeRow> callEdges = queryCallsOut(current, d);
            result.addAll(callEdges);

            Set<String> dispatchCandidates = new HashSet<>(current);
            for (EdgeRow e : callEdges) {
                if (!e.isExternal && e.target != null) {
                    if (visited.add(e.target)) frontier.addLast(e.target);
                    dispatchCandidates.add(e.target);
                }
            }

            for (String methodId : dispatchCandidates) {
                for (String impl : overrideImpls(methodId)) {
                    result.add(makeOverrideEdge(methodId, impl, d));
                    if (visited.add(impl)) frontier.addLast(impl);
                }
            }
        }
        return dedup(result);
    }

    List<EdgeRow> callsTo(List<String> seedIds, int depth) {
        if (seedIds.isEmpty()) return Collections.emptyList();
        int depthCap = Math.min(depth, MAX_DEPTH);

        List<EdgeRow> result = new ArrayList<>();
        Set<String> visited = new HashSet<>(seedIds);
        Deque<String> frontier = new ArrayDeque<>(seedIds);

        for (int d = 1; d <= depthCap && !frontier.isEmpty(); d++) {
            List<String> current = new ArrayList<>(frontier);
            frontier.clear();

            List<EdgeRow> callEdges = queryCallsIn(current, d);
            result.addAll(callEdges);

            List<String> bridged = new ArrayList<>();
            for (String node : current) {
                for (String ifaceMethod : overriddenIface(node)) {
                    if (visited.add(ifaceMethod)) {
                        result.add(makeOverrideEdge(ifaceMethod, node, d));
                        bridged.add(ifaceMethod);
                    }
                }
            }
            if (!bridged.isEmpty()) {
                List<EdgeRow> bridgedCallers = queryCallsIn(bridged, d);
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
