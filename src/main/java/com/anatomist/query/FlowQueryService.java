package com.anatomist.query;

import com.anatomist.json.Json;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
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

    public static final int MAX_TRAVERSAL_DEPTH = 50;
    public static final int MAX_PATH_DEPTH = 100;
    public static final int MAX_LIMIT = 10_000;

    private static final Set<String> DATA_RELATIONS = Set.of(
            "DEF_USE", "ARGUMENT_FLOW", "RETURN_FLOW",
            "CALL_ARGUMENT", "CALL_RETURN", "TAINT_FLOW");
    private static final Set<String> CONTROL_RELATIONS = Set.of(
            "CONTROL_FLOW", "CONDITION_FLOW", "GUARD_TRUE", "GUARD_FALSE");

    public record PathOptions(String sourceSlot,
                              String targetSlot,
                              boolean includeControl,
                              boolean includeException,
                              boolean taintOnly) {
        public static PathOptions dataOnly() {
            return new PathOptions(null, null, false, false, false);
        }

        public static PathOptions taint() {
            return new PathOptions(null, null, false, false, true);
        }
    }

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

    public Connection connection() {
        return connection;
    }

    public QueryEnvelope flowOf(String method,
                                boolean reverse,
                                int depth,
                                int limit) {
        requireDetailed(method);
        Set<String> starts = flowNodesFor(method);
        TraversalResult<Map<String, Object>> traversal = traverse(
                starts, reverse, depth, limit, false);
        QueryEnvelope envelope = new QueryEnvelope(
                "flow-of " + method + (reverse ? " --reverse" : ""), traversal.items());
        envelope.stats.put("start_nodes", starts.size());
        putTraversalStats(envelope, traversal, MAX_TRAVERSAL_DEPTH);
        envelope.stats.put("limit", safeLimit(limit));
        envelope.stats.put("truncated", traversal.limitTruncated());
        envelope.stats.put("limit_truncated", traversal.limitTruncated());
        envelope.stats.put("limit_cap_reached",
                traversal.limitTruncated() && safeLimit(limit) >= MAX_LIMIT);
        return envelope;
    }

    public QueryEnvelope path(String source, String target, int depth, boolean taintOnly) {
        return path(source, target, depth,
                taintOnly ? PathOptions.taint() : PathOptions.dataOnly());
    }

    public QueryEnvelope path(String source, String target, int depth, PathOptions options) {
        boolean fullCoverage = "full".equals(flowMode());
        if (options.taintOnly() && !fullCoverage) requireFullCoverage();
        if (!options.taintOnly() && !fullCoverage) {
            requireDetailed(source);
            requireDetailed(target);
        }
        Set<String> relations = pathRelations(options);
        Set<String> starts = endpointNodes(source,
                options.taintOnly() ? "TAINT_SOURCE" : null, options.sourceSlot());
        Set<String> targets = endpointNodes(target,
                options.taintOnly() ? "TAINT_SINK" : null, options.targetSlot());
        TraversalResult<Map<String, Object>> traversal = shortestPath(
                starts, targets, depth, options.taintOnly(), relations);
        List<Map<String, Object>> path = traversal.items();
        QueryEnvelope envelope = new QueryEnvelope(
                (options.taintOnly() ? "taint-path " : "flow-path ")
                        + source + " " + target, path);
        envelope.stats.put("found", !path.isEmpty());
        putTraversalStats(envelope, traversal, MAX_PATH_DEPTH);
        envelope.stats.put("source_candidates", starts.size());
        envelope.stats.put("target_candidates", targets.size());
        envelope.stats.put("source_slot", options.sourceSlot());
        envelope.stats.put("target_slot", options.targetSlot());
        envelope.stats.put("relations", relations.stream().sorted().toList());
        envelope.stats.put("flow_coverage", fullCoverage ? "full" : "partial");
        return envelope;
    }

    private static Set<String> pathRelations(PathOptions options) {
        Set<String> relations = new LinkedHashSet<>(DATA_RELATIONS);
        if (options.includeControl()) relations.addAll(CONTROL_RELATIONS);
        if (options.includeException()) relations.add("EXCEPTION_FLOW");
        return Collections.unmodifiableSet(relations);
    }

    public QueryEnvelope exceptionFlow(String method, int limit) {
        requireDetailed(method);
        List<Map<String, Object>> rows = rowsForMethod(method,
                "n.kind IN ('THROW','EXCEPTION','CATCH_PARAMETER')"
                        + " OR e.relation='EXCEPTION_FLOW'", limit);
        return new QueryEnvelope("exception-flow " + method, rows);
    }

    public QueryEnvelope guardsOf(String method, int limit) {
        requireDetailed(method);
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

    private void requireFullCoverage() {
        String mode = flowMode();
        if (!"full".equals(mode)) {
            throw new FlowCoverageException("FLOW_COVERAGE_INCOMPLETE",
                    "flow-path and taint-path require a full dataflow index; current mode is "
                            + mode);
        }
    }

    private void requireDetailed(String method) {
        List<String> methods = resolveMethodIds(method);
        if (methods.isEmpty()) return;
        String placeholders = String.join(",", Collections.nCopies(methods.size(), "?"));
        String sql = "SELECT count(*) FROM method_flow_coverage"
                + " WHERE detail_level='DETAIL' AND method_id IN (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < methods.size(); i++) statement.setString(i + 1, methods.get(i));
            try (ResultSet result = statement.executeQuery()) {
                int detailed = result.next() ? result.getInt(1) : 0;
                if (detailed != methods.size()) {
                    throw new FlowCoverageException("FLOW_DETAIL_NOT_INDEXED",
                            "detailed flow is not indexed for " + method
                                    + "; rebuild with --dataflow or a matching --dataflow-scope");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check flow coverage for " + method, e);
        }
    }

    private String flowMode() {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM project_meta WHERE key='dataflow_mode'");
             ResultSet result = statement.executeQuery()) {
            if (result.next()) return result.getString(1);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read dataflow profile", e);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM project_meta WHERE key='dataflow'");
             ResultSet result = statement.executeQuery()) {
            return result.next() && Boolean.parseBoolean(result.getString(1)) ? "full" : "off";
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read dataflow profile", e);
        }
    }

    private TraversalResult<Map<String, Object>> traverse(Set<String> starts,
                                                          boolean reverse,
                                                          int depth,
                                                          int limit,
                                                          boolean taintOnly) {
        int maxDepth = Math.max(1, Math.min(depth, MAX_TRAVERSAL_DEPTH));
        int maxRows = safeLimit(limit);
        Set<String> seenNodes = new LinkedHashSet<>(starts);
        Set<Long> seenEdges = new HashSet<>();
        Deque<NodeDepth> queue = new ArrayDeque<>();
        starts.forEach(node -> queue.add(new NodeDepth(node, 0)));
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> boundary = new LinkedHashSet<>();
        int reachedDepth = 0;
        boolean limitTruncated = false;
        while (!queue.isEmpty()) {
            NodeDepth current = queue.removeFirst();
            if (current.depth() >= maxDepth) {
                boundary.add(current.node());
                continue;
            }
            if (taintOnly && "SANITIZER".equals(nodeKind(current.node()))) continue;
            List<EdgeRecord> edges = edges(current.node(), reverse);
            for (EdgeRecord edge : edges) {
                if (!seenEdges.add(edge.id())) continue;
                if (rows.size() >= maxRows) {
                    limitTruncated = true;
                    break;
                }
                rows.add(edgeMap(edge, current.depth() + 1));
                String next = reverse ? edge.source() : edge.target();
                if (seenNodes.add(next)) queue.addLast(new NodeDepth(next, current.depth() + 1));
                reachedDepth = Math.max(reachedDepth, current.depth() + 1);
            }
            if (limitTruncated) break;
        }
        int frontierCount = limitTruncated ? 0
                : flowFrontierCount(boundary, reverse, taintOnly);
        return new TraversalResult<>(rows, depth, maxDepth, reachedDepth,
                frontierCount > 0, frontierCount, limitTruncated);
    }

    private TraversalResult<Map<String, Object>> shortestPath(Set<String> starts,
                                                               Set<String> targets,
                                                               int depth,
                                                               boolean taintOnly,
                                                               Set<String> relations) {
        int maxDepth = Math.max(1, Math.min(depth, MAX_PATH_DEPTH));
        if (starts.isEmpty() || targets.isEmpty()) {
            return new TraversalResult<>(List.of(), depth, maxDepth,
                    0, false, 0, false);
        }
        Deque<NodeDepth> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>(starts);
        Map<String, EdgeRecord> previous = new HashMap<>();
        Map<String, Integer> depths = new HashMap<>();
        Set<String> boundary = new LinkedHashSet<>();
        starts.forEach(node -> {
            queue.add(new NodeDepth(node, 0));
            depths.put(node, 0);
        });
        String found = null;
        while (!queue.isEmpty() && found == null) {
            NodeDepth current = queue.removeFirst();
            if (targets.contains(current.node())) {
                found = current.node();
                break;
            }
            if (current.depth() >= maxDepth) {
                boundary.add(current.node());
                continue;
            }
            if (taintOnly && "SANITIZER".equals(nodeKind(current.node()))) continue;
            for (EdgeRecord edge : edges(current.node(), false)) {
                if (!relations.contains(edge.relation())) continue;
                if (taintOnly && !matchesTaintSinkSlot(edge)) continue;
                if (seen.add(edge.target())) {
                    previous.put(edge.target(), edge);
                    depths.put(edge.target(), current.depth() + 1);
                    if (targets.contains(edge.target())) {
                        found = edge.target();
                        break;
                    }
                    queue.addLast(new NodeDepth(edge.target(), current.depth() + 1));
                }
            }
        }
        int reachedDepth = depths.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (found == null) {
            int frontierCount = flowPathFrontierCount(
                    boundary, seen, taintOnly, relations);
            return new TraversalResult<>(List.of(), depth, maxDepth, reachedDepth,
                    frontierCount > 0, frontierCount, false);
        }
        List<EdgeRecord> reversed = new ArrayList<>();
        String current = found;
        while (!starts.contains(current)) {
            EdgeRecord edge = previous.get(current);
            if (edge == null) {
                return new TraversalResult<>(List.of(), depth, maxDepth, reachedDepth,
                        false, 0, false);
            }
            reversed.add(edge);
            current = edge.source();
        }
        Collections.reverse(reversed);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < reversed.size(); i++) rows.add(edgeMap(reversed.get(i), i + 1));
        return new TraversalResult<>(rows, depth, maxDepth, rows.size(),
                false, 0, false);
    }

    private int flowFrontierCount(Collection<String> boundary,
                                  boolean reverse,
                                  boolean taintOnly) {
        int count = 0;
        for (String node : boundary) {
            if (taintOnly && "SANITIZER".equals(nodeKind(node))) continue;
            if (!edges(node, reverse).isEmpty()) count++;
        }
        return count;
    }

    private int flowPathFrontierCount(Collection<String> boundary,
                                      Set<String> seen,
                                      boolean taintOnly,
                                      Set<String> relations) {
        int count = 0;
        for (String node : boundary) {
            if (taintOnly && "SANITIZER".equals(nodeKind(node))) continue;
            boolean hidden = edges(node, false).stream()
                    .filter(edge -> relations.contains(edge.relation()))
                    .filter(edge -> !taintOnly || matchesTaintSinkSlot(edge))
                    .map(EdgeRecord::target)
                    .anyMatch(target -> !seen.contains(target));
            if (hidden) count++;
        }
        return count;
    }

    private static void putTraversalStats(QueryEnvelope envelope,
                                          TraversalResult<?> traversal,
                                          int maximumDepth) {
        envelope.stats.put("depth_requested", traversal.requestedDepth());
        envelope.stats.put("depth_effective", traversal.effectiveDepth());
        envelope.stats.put("max_depth", traversal.reachedDepth());
        envelope.stats.put("depth_truncated", traversal.depthTruncated());
        envelope.stats.put("frontier_count", traversal.frontierCount());
        envelope.stats.put("depth_limit_reached",
                traversal.depthTruncated() && traversal.effectiveDepth() >= maximumDepth);
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
                       s.kind,s.label,s.line,t.kind,t.label,t.line,t.metadata
                FROM flow_edges e
                JOIN flow_nodes s ON s.id=e.source_node
                JOIN flow_nodes t ON t.id=e.target_node
                JOIN flow_nodes n ON n.id=e.target_node
                WHERE e.method_id IN (%s) AND (%s)
                ORDER BY e.source_file,s.line,e.relation,e.source_node,e.target_node,
                         COALESCE(e.context,''),e.confidence,e.id LIMIT ?
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
                       s.kind,s.label,s.line,t.kind,t.label,t.line,t.metadata
                FROM flow_edges e
                JOIN flow_nodes s ON s.id=e.source_node
                JOIN flow_nodes t ON t.id=e.target_node
                WHERE %s=?
                ORDER BY e.relation,e.target_node,e.source_node,
                         COALESCE(e.context,''),e.confidence,e.id
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
                result.getString(13), result.getString(14), result.getInt(15),
                result.getString(16));
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
                "SELECT id FROM flow_nodes WHERE method_id IN (" + placeholders
                        + ") ORDER BY id")) {
            for (int i = 0; i < methods.size(); i++) statement.setString(i + 1, methods.get(i));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) out.add(result.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to resolve flow nodes", e);
        }
        return out;
    }

    private Set<String> endpointNodes(String input, String defaultKind, String slot) {
        validateEndpointSlot(slot);
        if (input == null || input.isBlank() || "*".equals(input)) {
            if (defaultKind == null) return Set.of();
            return nodesByKind(defaultKind, slot);
        }
        Set<String> exactNode = exactNode(input, defaultKind, slot);
        if (!exactNode.isEmpty()) return exactNode;

        List<String> methods = resolveMethodIds(input);
        if (methods.size() > 1) {
            throw new FlowCoverageException("FLOW_ENDPOINT_AMBIGUOUS",
                    "flow endpoint " + input + " matches multiple methods: "
                            + String.join(", ", methods));
        }
        if (methods.isEmpty()) return Set.of();
        return nodesForMethod(methods.getFirst(), defaultKind, slot);
    }

    private Set<String> exactNode(String input, String defaultKind, String slot) {
        Set<String> out = new LinkedHashSet<>();
        String selector = selector("n");
        String sql = "SELECT n.id FROM flow_nodes n WHERE n.id=?"
                + kindAndSlotPredicate(defaultKind, slot) + selector + " ORDER BY n.id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, input);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) out.add(result.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to resolve flow endpoint " + input, e);
        }
        return out;
    }

    private Set<String> nodesForMethod(String method, String kind, String slot) {
        Set<String> out = new LinkedHashSet<>();
        String sql = "SELECT n.id FROM flow_nodes n WHERE n.method_id=?"
                + kindAndSlotPredicate(kind, slot) + selector("n") + " ORDER BY n.id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, method);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) out.add(result.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query flow nodes for " + method, e);
        }
        if (slot != null && out.isEmpty()) {
            throw new FlowCoverageException("FLOW_ENDPOINT_SLOT_INVALID",
                    "method " + method + " has no flow endpoint for slot " + slot);
        }
        return out;
    }

    private Set<String> nodesByKind(String kind, String slot) {
        Set<String> out = new LinkedHashSet<>();
        String sql = "SELECT n.id FROM flow_nodes n WHERE n.kind=?"
                + slotPredicate(slot) + selector("n") + " ORDER BY n.id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, kind);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) out.add(result.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query flow nodes by kind", e);
        }
        return out;
    }

    private static void validateEndpointSlot(String slot) {
        if (slot == null || "return".equals(slot) || "throw".equals(slot)) return;
        if (!slot.startsWith("arg:") || slot.length() == 4) {
            throw new FlowCoverageException("FLOW_ENDPOINT_SLOT_INVALID",
                    "slot must be arg:N, return, or throw: " + slot);
        }
        for (int i = 4; i < slot.length(); i++) {
            if (!Character.isDigit(slot.charAt(i))) {
                throw new FlowCoverageException("FLOW_ENDPOINT_SLOT_INVALID",
                        "slot must be arg:N, return, or throw: " + slot);
            }
        }
    }

    private static String kindAndSlotPredicate(String kind, String slot) {
        String kindPredicate = kind == null ? "" : " AND n.kind='" + kind + "'";
        return kindPredicate + slotPredicate(slot);
    }

    private static String slotPredicate(String slot) {
        if (slot == null) return "";
        if ("return".equals(slot)) return " AND n.kind='RETURN'";
        if ("throw".equals(slot)) return " AND n.kind IN ('THROW','EXCEPTION')";
        return " AND n.kind='PARAMETER' AND n.slot='" + slot + "'";
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
                "SELECT DISTINCT method_id FROM flow_nodes"
                        + " WHERE method_id=? OR method_id LIKE ? ORDER BY method_id")) {
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

    private static boolean matchesTaintSinkSlot(EdgeRecord edge) {
        if (!"TAINT_SINK".equals(edge.targetKind())) return true;
        String slot = metadataString(edge.targetMetadata(), "taint_sink_slot");
        return slot == null || ("ARGUMENT_FLOW".equals(edge.relation())
                && slot.equals(edge.context()));
    }

    private static String metadataString(String metadata, String key) {
        if (metadata == null) return null;
        try {
            Object tree = Json.parseTree(metadata);
            if (tree instanceof Map<?, ?> map && map.get(key) != null) {
                return String.valueOf(map.get(key));
            }
        } catch (RuntimeException ignored) {
            // Invalid node metadata should not make a read-only path query fail.
        }
        return null;
    }

    private static int safeLimit(int limit) {
        return limit <= 0 ? 1000 : Math.min(limit, MAX_LIMIT);
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignore) {
        }
    }

    private record NodeDepth(String node, int depth) {}
    private record EdgeRecord(long id, String source, String target, String relation,
                              String method, String sourceFile, String confidence,
                              String context, String metadata,
                              String sourceKind, String sourceLabel, int sourceLine,
                              String targetKind, String targetLabel, int targetLine,
                              String targetMetadata) {}
}
