package com.anatomist.query;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only flow graph traversal and path queries. */
public final class FlowQueryService implements AutoCloseable {

    private final Connection connection;
    private String module;
    private String scope = "MAIN";

    public FlowQueryService(Path database) {
        try {
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open flow index: " + database, e);
        }
    }

    public void select(String module, String scope) {
        this.module = module == null || module.isBlank() ? null : module;
        this.scope = scope == null || scope.isBlank() ? "MAIN" : scope.toUpperCase();
        if (!Set.of("MAIN", "TEST", "GENERATED", "ALL").contains(this.scope)) {
            throw new IllegalArgumentException(
                    "scope must be MAIN, TEST, GENERATED, or ALL: " + scope);
        }
    }

    public QueryEnvelope flowOf(String method,
                                boolean reverse,
                                int depth,
                                int limit) {
        Set<String> starts = flowNodesFor(method);
        Traversal traversal = traverse(starts, reverse, depth, limit, false);
        QueryEnvelope envelope = new QueryEnvelope(
                "flow-of " + method + (reverse ? " --reverse" : ""), traversal.rows());
        envelope.stats.put("start_nodes", starts.size());
        envelope.stats.put("max_depth", traversal.maxDepth());
        envelope.stats.put("truncated", traversal.truncated());
        return envelope;
    }

    public QueryEnvelope path(String source, String target, int depth, boolean taintOnly) {
        Set<String> starts = endpointNodes(source, taintOnly ? "TAINT_SOURCE" : null);
        Set<String> targets = endpointNodes(target, taintOnly ? "TAINT_SINK" : null);
        List<Map<String, Object>> path = shortestPath(starts, targets, depth, taintOnly);
        QueryEnvelope envelope = new QueryEnvelope(
                (taintOnly ? "taint-path " : "flow-path ") + source + " " + target, path);
        envelope.stats.put("found", !path.isEmpty());
        envelope.stats.put("max_depth", Math.max(1, depth));
        envelope.stats.put("source_candidates", starts.size());
        envelope.stats.put("target_candidates", targets.size());
        return envelope;
    }

    public QueryEnvelope exceptionFlow(String method, int limit) {
        List<Map<String, Object>> rows = rowsForMethod(method,
                "n.kind IN ('THROW','EXCEPTION','CATCH_PARAMETER')"
                        + " OR e.relation='EXCEPTION_FLOW'", limit);
        return new QueryEnvelope("exception-flow " + method, rows);
    }

    public QueryEnvelope guardsOf(String method, int limit) {
        List<Map<String, Object>> rows = rowsForMethod(method,
                "e.relation IN ('GUARD_TRUE','GUARD_FALSE','CONDITION_FLOW')", limit);
        return new QueryEnvelope("guards-of " + method, rows);
    }

    public QueryEnvelope summaries(String method, int limit) {
        List<String> methods = resolveMethodIds(method);
        if (methods.isEmpty()) return new QueryEnvelope("flow-summary " + method, List.of());
        String placeholders = String.join(",", Collections.nCopies(methods.size(), "?"));
        String sql = "SELECT method_id,input_slot,output_slot,relation,source_file,confidence,metadata"
                + " FROM method_flow_summaries WHERE method_id IN (" + placeholders + ")"
                + " ORDER BY method_id,input_slot,output_slot LIMIT ?";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (String id : methods) statement.setString(index++, id);
            statement.setInt(index, safeLimit(limit));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("method_id", result.getString(1));
                    row.put("input_slot", result.getString(2));
                    row.put("output_slot", result.getString(3));
                    row.put("relation", result.getString(4));
                    row.put("source_file", result.getString(5));
                    row.put("confidence", result.getString(6));
                    if (result.getString(7) != null) row.put("metadata", result.getString(7));
                    rows.add(row);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query method flow summaries", e);
        }
        return new QueryEnvelope("flow-summary " + method, rows);
    }

    private Traversal traverse(Set<String> starts,
                               boolean reverse,
                               int depth,
                               int limit,
                               boolean taintOnly) {
        int maxDepth = Math.max(1, Math.min(depth, 50));
        int maxRows = safeLimit(limit);
        Set<String> seenNodes = new LinkedHashSet<>(starts);
        Set<Long> seenEdges = new HashSet<>();
        Deque<NodeDepth> queue = new ArrayDeque<>();
        starts.forEach(node -> queue.add(new NodeDepth(node, 0)));
        List<Map<String, Object>> rows = new ArrayList<>();
        int reachedDepth = 0;
        boolean truncated = false;
        while (!queue.isEmpty()) {
            NodeDepth current = queue.removeFirst();
            if (current.depth() >= maxDepth) continue;
            if (taintOnly && "SANITIZER".equals(nodeKind(current.node()))) continue;
            List<EdgeRecord> edges = edges(current.node(), reverse);
            for (EdgeRecord edge : edges) {
                if (!seenEdges.add(edge.id())) continue;
                if (rows.size() >= maxRows) {
                    truncated = true;
                    break;
                }
                rows.add(edgeMap(edge, current.depth() + 1));
                String next = reverse ? edge.source() : edge.target();
                if (seenNodes.add(next)) queue.addLast(new NodeDepth(next, current.depth() + 1));
                reachedDepth = Math.max(reachedDepth, current.depth() + 1);
            }
            if (truncated) break;
        }
        return new Traversal(rows, reachedDepth, truncated);
    }

    private List<Map<String, Object>> shortestPath(Set<String> starts,
                                                   Set<String> targets,
                                                   int depth,
                                                   boolean taintOnly) {
        if (starts.isEmpty() || targets.isEmpty()) return List.of();
        int maxDepth = Math.max(1, Math.min(depth, 100));
        Deque<NodeDepth> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>(starts);
        Map<String, EdgeRecord> previous = new HashMap<>();
        starts.forEach(node -> queue.add(new NodeDepth(node, 0)));
        String found = null;
        while (!queue.isEmpty() && found == null) {
            NodeDepth current = queue.removeFirst();
            if (targets.contains(current.node())) {
                found = current.node();
                break;
            }
            if (current.depth() >= maxDepth) continue;
            if (taintOnly && "SANITIZER".equals(nodeKind(current.node()))) continue;
            for (EdgeRecord edge : edges(current.node(), false)) {
                if (seen.add(edge.target())) {
                    previous.put(edge.target(), edge);
                    if (targets.contains(edge.target())) {
                        found = edge.target();
                        break;
                    }
                    queue.addLast(new NodeDepth(edge.target(), current.depth() + 1));
                }
            }
        }
        if (found == null) return List.of();
        List<EdgeRecord> reversed = new ArrayList<>();
        String current = found;
        while (!starts.contains(current)) {
            EdgeRecord edge = previous.get(current);
            if (edge == null) return List.of();
            reversed.add(edge);
            current = edge.source();
        }
        Collections.reverse(reversed);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < reversed.size(); i++) rows.add(edgeMap(reversed.get(i), i + 1));
        return rows;
    }

    private List<Map<String, Object>> rowsForMethod(String method,
                                                    String predicate,
                                                    int limit) {
        List<String> methods = resolveMethodIds(method);
        if (methods.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(methods.size(), "?"));
        String sql = """
                SELECT e.id,e.source_node,e.target_node,e.relation,e.method_id,e.source_file,
                       e.confidence,e.context,e.metadata,
                       s.kind,s.label,s.line,t.kind,t.label,t.line
                FROM flow_edges e
                JOIN flow_nodes s ON s.id=e.source_node
                JOIN flow_nodes t ON t.id=e.target_node
                JOIN flow_nodes n ON n.id=e.target_node
                WHERE e.method_id IN (%s) AND (%s)
                ORDER BY e.source_file,s.line,e.id LIMIT ?
                """.formatted(placeholders, predicate);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (String id : methods) statement.setString(index++, id);
            statement.setInt(index, safeLimit(limit));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(edgeMap(readEdge(result), 1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query flow facts for " + method, e);
        }
        return rows;
    }

    private List<EdgeRecord> edges(String node, boolean reverse) {
        String sql = """
                SELECT e.id,e.source_node,e.target_node,e.relation,e.method_id,e.source_file,
                       e.confidence,e.context,e.metadata,
                       s.kind,s.label,s.line,t.kind,t.label,t.line
                FROM flow_edges e
                JOIN flow_nodes s ON s.id=e.source_node
                JOIN flow_nodes t ON t.id=e.target_node
                WHERE %s=? ORDER BY e.id
                """.formatted(reverse ? "e.target_node" : "e.source_node");
        List<EdgeRecord> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, node);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(readEdge(result));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to traverse flow graph", e);
        }
        return rows;
    }

    private EdgeRecord readEdge(ResultSet result) throws SQLException {
        return new EdgeRecord(
                result.getLong(1), result.getString(2), result.getString(3),
                result.getString(4), result.getString(5), result.getString(6),
                result.getString(7), result.getString(8), result.getString(9),
                result.getString(10), result.getString(11), result.getInt(12),
                result.getString(13), result.getString(14), result.getInt(15));
    }

    private static Map<String, Object> edgeMap(EdgeRecord edge, int depth) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("depth", depth);
        row.put("relation", edge.relation());
        row.put("source_node", edge.source());
        row.put("source_kind", edge.sourceKind());
        row.put("source_label", edge.sourceLabel());
        row.put("source_line", edge.sourceLine());
        row.put("target_node", edge.target());
        row.put("target_kind", edge.targetKind());
        row.put("target_label", edge.targetLabel());
        row.put("target_line", edge.targetLine());
        row.put("method_id", edge.method());
        row.put("source_file", edge.sourceFile());
        row.put("confidence", edge.confidence());
        if (edge.context() != null) row.put("context", edge.context());
        if (edge.metadata() != null) row.put("metadata", edge.metadata());
        return row;
    }

    private Set<String> flowNodesFor(String method) {
        List<String> methods = resolveMethodIds(method);
        if (methods.isEmpty()) return Set.of();
        String placeholders = String.join(",", Collections.nCopies(methods.size(), "?"));
        Set<String> out = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM flow_nodes WHERE method_id IN (" + placeholders + ")")) {
            for (int i = 0; i < methods.size(); i++) statement.setString(i + 1, methods.get(i));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) out.add(result.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to resolve flow nodes", e);
        }
        return out;
    }

    private Set<String> endpointNodes(String input, String defaultKind) {
        if (input == null || input.isBlank() || "*".equals(input)) {
            return defaultKind == null ? Set.of() : nodesByKind(defaultKind);
        }
        Set<String> exact = new LinkedHashSet<>();
        String selector = selector("n");
        String sql = "SELECT n.id FROM flow_nodes n WHERE "
                + "(n.id=? OR n.method_id=? OR n.label=? OR n.method_id LIKE ?)"
                + (defaultKind == null ? "" : " AND n.kind=?") + selector;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, input);
            statement.setString(2, input);
            statement.setString(3, input);
            statement.setString(4, "%" + input + "%");
            if (defaultKind != null) statement.setString(5, defaultKind);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) exact.add(result.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to resolve flow endpoint " + input, e);
        }
        if (!exact.isEmpty()) return exact;
        return flowNodesFor(input);
    }

    private Set<String> nodesByKind(String kind) {
        Set<String> out = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT n.id FROM flow_nodes n WHERE n.kind=?" + selector("n"))) {
            statement.setString(1, kind);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) out.add(result.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query flow nodes by kind", e);
        }
        return out;
    }

    private List<String> resolveMethodIds(String input) {
        if (input == null || input.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        String selector = selector("n");
        String sql = """
                SELECT DISTINCT n.id
                FROM nodes n
                WHERE n.kind IN ('METHOD','LAMBDA','METHOD_REF')
                  AND (n.id=? OR n.symbol_id=? OR n.qualified_name=?
                       OR n.symbol_id LIKE ? OR n.qualified_name LIKE ?)
                """.replace("\n", " ") + selector + " ORDER BY n.id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, input);
            statement.setString(2, input);
            statement.setString(3, input);
            statement.setString(4, "%" + normalizeMethodSearch(input) + "%");
            statement.setString(5, "%" + input.replace('#', '.') + "%");
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) out.add(result.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to resolve method " + input, e);
        }
        if (!out.isEmpty()) return out;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT DISTINCT method_id FROM flow_nodes WHERE method_id=? OR method_id LIKE ?")) {
            statement.setString(1, input);
            statement.setString(2, "%" + normalizeMethodSearch(input) + "%");
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) out.add(result.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to resolve flow method " + input, e);
        }
        return out;
    }

    private String selector(String alias) {
        StringBuilder out = new StringBuilder();
        if (!"ALL".equals(scope)) out.append(" AND ").append(alias).append(".scope='")
                .append(scope.replace("'", "''")).append("'");
        if (module != null) out.append(" AND ").append(alias).append(".module='")
                .append(module.replace("'", "''")).append("'");
        return out.toString();
    }

    private static String normalizeMethodSearch(String input) {
        int parenthesis = input.indexOf('(');
        return parenthesis >= 0 ? input.substring(0, parenthesis) : input;
    }

    private String nodeKind(String node) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT kind FROM flow_nodes WHERE id=?")) {
            statement.setString(1, node);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read flow node kind", e);
        }
    }

    private static int safeLimit(int limit) {
        return limit <= 0 ? 1000 : Math.min(limit, 10_000);
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignore) {
        }
    }

    private record NodeDepth(String node, int depth) {}
    private record Traversal(List<Map<String, Object>> rows, int maxDepth, boolean truncated) {}
    private record EdgeRecord(long id, String source, String target, String relation,
                              String method, String sourceFile, String confidence,
                              String context, String metadata,
                              String sourceKind, String sourceLabel, int sourceLine,
                              String targetKind, String targetLabel, int targetLine) {}
}
