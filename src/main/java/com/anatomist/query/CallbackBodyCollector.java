package com.anatomist.query;

import com.anatomist.model.GraphConstants;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.anatomist.query.QueryInfra.rethrow;

final class CallbackBodyCollector {

    private final Connection conn;

    CallbackBodyCollector(Connection conn) {
        this.conn = conn;
    }

    Map<String, List<String>> collect(List<String> methods, Set<String> visited) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String method : methods) {
            out.put(method, collectOne(method, visited));
        }
        return out;
    }

    private List<String> collectOne(String method, Set<String> visited) {
        List<String> bodies = new ArrayList<>();
        Set<String> localSeen = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(method);
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            for (NodeRef child : containsChildren(cur)) {
                if (!localSeen.add(child.id)) continue;
                switch (child.kind) {
                    case GraphConstants.Kind.LAMBDA, GraphConstants.Kind.METHOD_REF -> {
                        if (visited.add(child.id)) bodies.add(child.id);
                        stack.push(child.id);
                    }
                    case GraphConstants.Kind.ANONYMOUS_CLASS -> stack.push(child.id);
                    case GraphConstants.Kind.METHOD -> {
                        if (child.id.contains("$anon@") || child.id.contains("$lambda@")) {
                            if (visited.add(child.id)) bodies.add(child.id);
                            stack.push(child.id);
                        }
                    }
                    default -> { /* ordinary nested type / member: ignore */ }
                }
            }
        }
        return bodies;
    }

    private List<NodeRef> containsChildren(String parentId) {
        List<NodeRef> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT n.id, n.kind FROM edges e JOIN nodes n ON n.id = e.target_id "
                        + " WHERE e.source_id = ? AND e.relation = '"
                        + GraphConstants.Relation.CONTAINS + "'")) {
            ps.setString(1, parentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new NodeRef(rs.getString(1), rs.getString(2)));
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }
        return out;
    }

    private record NodeRef(String id, String kind) {}
}
