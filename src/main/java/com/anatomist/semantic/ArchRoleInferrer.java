package com.anatomist.semantic;

import com.anatomist.model.ArchRole;
import com.anatomist.store.SqliteStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Infers architecture roles (DDD layer) for type-level nodes.
 * L1: annotation-based rules (high confidence, deterministic).
 * L2: call-pattern heuristics (medium confidence).
 */
public class ArchRoleInferrer {

    private final SqliteStore store;

    public ArchRoleInferrer(SqliteStore store) {
        this.store = store;
    }

    static final String ARCH_ROLE_FQN = "com.anatomist.annotations.ArchRole";
    private static final Set<String> VALID_ROLES = Set.of(
            "ENTRY", "APPLICATION", "DOMAIN_SERVICE", "DOMAIN_MODEL",
            "REPOSITORY", "ADAPTER", "INFRASTRUCTURE");

    public List<ArchRole> infer() {
        try {
            Connection conn = store.connection();

            // L0: explicit @ArchRole annotations in source code
            List<ArchRole> l0 = inferExplicit(conn);
            Set<String> resolved = new HashSet<>();
            for (ArchRole r : l0) resolved.add(r.nodeId);

            Map<String, Set<String>> annotsByNode = loadAnnotations(conn);
            List<ArchRole> l1 = inferL1(annotsByNode);
            for (ArchRole r : l1) {
                if (!resolved.contains(r.nodeId)) resolved.add(r.nodeId);
                else l1 = l1.stream().filter(x -> !x.nodeId.equals(r.nodeId) || x == r).toList();
            }

            // Remove L1 results that conflict with explicit
            Set<String> l0Ids = new HashSet<>();
            for (ArchRole r : l0) l0Ids.add(r.nodeId);
            l1 = l1.stream().filter(r -> !l0Ids.contains(r.nodeId)).toList();
            for (ArchRole r : l1) resolved.add(r.nodeId);

            List<ArchRole> l2 = inferL2(conn, annotsByNode, resolved);

            List<ArchRole> all = new ArrayList<>(l0);
            all.addAll(l1);
            all.addAll(l2);
            return all;
        } catch (SQLException e) {
            throw new RuntimeException("ArchRoleInferrer failed", e);
        }
    }

    List<ArchRole> inferExplicit(Connection conn) throws SQLException {
        List<ArchRole> result = new ArrayList<>();
        String sql = "SELECT node_id, attributes FROM annotations WHERE annotation_fqn = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ARCH_ROLE_FQN);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String nodeId = rs.getString(1);
                    String attrs = rs.getString(2);
                    String role = extractRoleFromAttributes(attrs);
                    if (role != null && VALID_ROLES.contains(role)) {
                        result.add(new ArchRole(nodeId, role, "explicit", "@ArchRole"));
                    }
                }
            }
        }
        return result;
    }

    static String extractRoleFromAttributes(String attrsJson) {
        if (attrsJson == null || attrsJson.isEmpty()) return null;
        // attributes JSON is like {"value": "APPLICATION"} or {"value": "Category.APPLICATION"}
        int idx = attrsJson.indexOf("\"value\"");
        if (idx < 0) return null;
        int colonIdx = attrsJson.indexOf(':', idx);
        if (colonIdx < 0) return null;
        int firstQuote = attrsJson.indexOf('"', colonIdx + 1);
        if (firstQuote < 0) return null;
        int lastQuote = attrsJson.indexOf('"', firstQuote + 1);
        if (lastQuote < 0) return null;
        String val = attrsJson.substring(firstQuote + 1, lastQuote);
        // handle "Category.APPLICATION" → "APPLICATION"
        if (val.contains(".")) val = val.substring(val.lastIndexOf('.') + 1);
        return val;
    }

    List<ArchRole> inferL1(Map<String, Set<String>> annotsByNode) {
        List<ArchRole> result = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : annotsByNode.entrySet()) {
            String nodeId = entry.getKey();
            Set<String> anns = entry.getValue();
            ArchRole role = matchL1(nodeId, anns);
            if (role != null) result.add(role);
        }
        return result;
    }

    static ArchRole matchL1(String nodeId, Set<String> annotations) {
        for (String ann : annotations) {
            String simple = ann.contains(".") ? ann.substring(ann.lastIndexOf('.') + 1) : ann;
            switch (simple) {
                case "RestController":
                case "Controller":
                    return new ArchRole(nodeId, "ENTRY", "auto_annotation", "@" + simple);
                case "Repository":
                    return new ArchRole(nodeId, "REPOSITORY", "auto_annotation", "@" + simple);
                case "Configuration":
                case "Aspect":
                    return new ArchRole(nodeId, "INFRASTRUCTURE", "auto_annotation", "@" + simple);
                case "Entity":
                    return new ArchRole(nodeId, "DOMAIN_MODEL", "auto_annotation", "@" + simple);
                default:
                    break;
            }
        }
        return null;
    }

    List<ArchRole> inferL2(Connection conn, Map<String, Set<String>> annotsByNode, Set<String> resolved)
            throws SQLException {
        List<ArchRole> result = new ArrayList<>();

        Set<String> serviceNodes = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : annotsByNode.entrySet()) {
            if (resolved.contains(entry.getKey())) continue;
            Set<String> anns = entry.getValue();
            for (String ann : anns) {
                String simple = ann.contains(".") ? ann.substring(ann.lastIndexOf('.') + 1) : ann;
                if ("Service".equals(simple)) {
                    serviceNodes.add(entry.getKey());
                    break;
                }
            }
        }

        for (String nodeId : serviceNodes) {
            String role = classifyServiceByCallPattern(conn, nodeId);
            result.add(new ArchRole(nodeId, role, "auto_call_pattern", "call pattern analysis"));
        }

        // Detect ADAPTER: classes (not already resolved) that call external HTTP/MQ targets
        Set<String> adapterCandidates = detectAdapters(conn, resolved, serviceNodes);
        for (String nodeId : adapterCandidates) {
            result.add(new ArchRole(nodeId, "ADAPTER", "auto_call_pattern", "calls external HTTP/MQ client"));
        }

        return result;
    }

    /**
     * Classify @Service as APPLICATION or DOMAIN_SERVICE based on call patterns.
     * APPLICATION: mostly delegates (calls other services/repos), little own logic.
     * DOMAIN_SERVICE: has business computation (fewer delegation calls relative to complexity).
     */
    String classifyServiceByCallPattern(Connection conn, String serviceNodeId) throws SQLException {
        // Get all methods of this class
        String methodsSql = "SELECT target_id FROM edges WHERE source_id = ? AND relation = 'CONTAINS' AND is_external = 0";
        List<String> methods = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(methodsSql)) {
            ps.setString(1, serviceNodeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) methods.add(rs.getString(1));
            }
        }
        if (methods.isEmpty()) return "APPLICATION";

        // Count outgoing CALLS from those methods to other types' methods
        int totalCalls = 0;
        int externalCalls = 0;
        for (String mid : methods) {
            String callsSql = "SELECT target_id, is_external FROM edges WHERE source_id = ? AND relation = 'CALLS'";
            try (PreparedStatement ps = conn.prepareStatement(callsSql)) {
                ps.setString(1, mid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        totalCalls++;
                        if (rs.getInt(2) == 1) externalCalls++;
                        else {
                            String targetId = rs.getString(1);
                            if (targetId != null && !targetId.startsWith(serviceNodeId + "#")) {
                                externalCalls++;
                            }
                        }
                    }
                }
            }
        }

        // High ratio of calls to other classes → orchestrator (APPLICATION)
        // Low ratio → business computation (DOMAIN_SERVICE)
        if (totalCalls == 0) return "DOMAIN_SERVICE";
        double delegationRatio = (double) externalCalls / totalCalls;
        return delegationRatio >= 0.5 ? "APPLICATION" : "DOMAIN_SERVICE";
    }

    private Set<String> detectAdapters(Connection conn, Set<String> resolved, Set<String> serviceNodes)
            throws SQLException {
        Set<String> adapterNodes = new HashSet<>();
        // Find type nodes whose methods call known external HTTP/MQ patterns
        String sql = "SELECT DISTINCT n_type.id FROM nodes n_type "
                + "JOIN edges contains ON contains.source_id = n_type.id AND contains.relation = 'CONTAINS' AND contains.is_external = 0 "
                + "JOIN edges calls ON calls.source_id = contains.target_id AND calls.relation = 'CALLS' AND calls.is_external = 1 "
                + "WHERE n_type.kind IN ('CLASS','INTERFACE') "
                + "AND (calls.external_target_fqn LIKE 'org.springframework.web.client.%' "
                + "  OR calls.external_target_fqn LIKE 'java.net.http.%' "
                + "  OR calls.external_target_fqn LIKE 'org.apache.http.%' "
                + "  OR calls.external_target_fqn LIKE 'okhttp3.%' "
                + "  OR calls.external_target_fqn LIKE 'feign.%' "
                + "  OR calls.external_target_fqn LIKE 'org.springframework.kafka.%' "
                + "  OR calls.external_target_fqn LIKE 'org.springframework.amqp.%' "
                + "  OR calls.external_target_fqn LIKE 'org.springframework.jms.%')";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String nodeId = rs.getString(1);
                if (!resolved.contains(nodeId) && !serviceNodes.contains(nodeId)) {
                    adapterNodes.add(nodeId);
                }
            }
        }
        return adapterNodes;
    }

    private Map<String, Set<String>> loadAnnotations(Connection conn) throws SQLException {
        Map<String, Set<String>> result = new HashMap<>();
        // Only load annotations for type-level nodes
        String sql = "SELECT a.node_id, a.annotation_fqn FROM annotations a "
                + "JOIN nodes n ON a.node_id = n.id "
                + "WHERE n.kind IN ('CLASS','INTERFACE','ENUM','RECORD')";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
            }
        }
        return result;
    }
}
