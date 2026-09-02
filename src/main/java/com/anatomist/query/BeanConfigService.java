package com.anatomist.query;

import com.anatomist.json.Json;
import com.anatomist.model.GraphConstants;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BeanConfigService {

    private final Connection conn;

    public BeanConfigService(Connection conn) {
        this.conn = conn;
    }

    public List<Map<String, Object>> beanConfig(String target, String property) {
        return beanConfigPaged(target, property, null, "MAIN", 20, 0).items();
    }

    public PagedResult<Map<String, Object>> beanConfigPaged(String target, String property,
                                                            String module, String scope,
                                                            int limit, int offset) {
        List<Node> matches = findBeans(target, module, scope);
        int total = matches.size();
        int safeOffset = Math.max(0, Math.min(offset, total));
        int end = Math.min(safeOffset + limit, total);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Node node : matches.subList(safeOffset, end)) {
            Map<String, Object> bean = new LinkedHashMap<>();
            bean.put("bean_id", node.id);
            bean.put("label", node.label);
            bean.put("source_file", node.sourceFile);
            bean.put("source_location", node.sourceLocation);
            bean.put("module", node.module);
            bean.put("scope", node.scope);
            bean.put("producer_id", node.producerId);
            bean.put("metadata", parseMetadata(node.metadata));
            List<Map<String, Object>> children = childrenOf(node.id, property);
            bean.put("children", children);
            out.add(bean);
        }
        return new PagedResult<>(out, total, end < total, safeOffset);
    }

    private List<Node> findBeans(String target, String module, String scope) {
        String like = "%" + escapeLike(target) + "%";
        StringBuilder sql = new StringBuilder("""
                SELECT id,label,source_file,source_location,module,scope,metadata,producer_id
                FROM nodes
                WHERE kind=? AND producer_id=?
                AND (label=? OR label LIKE ? ESCAPE '\\' OR qualified_name LIKE ? ESCAPE '\\' OR id LIKE ? ESCAPE '\\')
                """);
        List<Object> args = new ArrayList<>();
        args.add(GraphConstants.Kind.BEAN);
        args.add(com.anatomist.model.ProducerIds.SPRING_XML);
        args.add(target);
        args.add(like);
        args.add(like);
        args.add(like);
        if (module != null && !module.isBlank()) {
            sql.append(" AND module=?");
            args.add(module);
        }
        if (scope != null && !"ALL".equalsIgnoreCase(scope)) {
            sql.append(" AND scope=?");
            args.add(scope.toUpperCase());
        }
        sql.append(" ORDER BY CASE WHEN label=? THEN 0 ELSE 1 END, source_file, label");
        args.add(target);
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                List<Node> rows = new ArrayList<>();
                while (rs.next()) rows.add(readNode(rs));
                return rows;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query bean config", e);
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private List<Map<String, Object>> childrenOf(String parentId, String property) {
        String sql = """
                SELECT n.id,n.label,n.kind,n.source_file,n.source_location,n.metadata,n.producer_id,
                       e.target_id,e.external_target_fqn,e.producer_id AS ref_producer_id
                FROM edges ce
                JOIN nodes n ON n.id=ce.target_id
                LEFT JOIN edges e ON e.source_id=n.id AND e.relation=?
                WHERE ce.source_id=? AND ce.relation IN (?,?)
                ORDER BY n.source_location,n.id
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, GraphConstants.Relation.XML_REFERS_TO);
            ps.setString(2, parentId);
            ps.setString(3, GraphConstants.Relation.CONFIGURES);
            ps.setString(4, GraphConstants.Relation.XML_CONTAINS);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = nodeMap(rs);
                    if (property != null
                            && !property.equals(String.valueOf(row.get("name")))) {
                        continue;
                    }
                    row.put("children", childrenOf((String) row.get("id"), null));
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query bean config children", e);
        }
    }

    private static Map<String, Object> nodeMap(ResultSet rs) throws SQLException {
        Map<String, Object> meta = parseMetadata(rs.getString("metadata"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", rs.getString("id"));
        out.put("label", rs.getString("label"));
        out.put("kind", rs.getString("kind"));
        out.put("source_file", rs.getString("source_file"));
        out.put("source_location", rs.getString("source_location"));
        out.putAll(meta);
        out.put("producer_id", rs.getString("producer_id"));
        String target = rs.getString("target_id");
        String external = rs.getString("external_target_fqn");
        if (target != null) out.put("ref_target", target);
        if (external != null) out.put("external_ref_target", external);
        if (target != null || external != null) out.put("ref_producer_id", rs.getString("ref_producer_id"));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) return new LinkedHashMap<>();
        Object parsed = Json.parseTree(metadata);
        if (parsed instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
            return out;
        }
        return new LinkedHashMap<>();
    }

    private static Node readNode(ResultSet rs) throws SQLException {
        return new Node(rs.getString("id"), rs.getString("label"), rs.getString("source_file"),
                rs.getString("source_location"), rs.getString("module"), rs.getString("scope"),
                rs.getString("metadata"), rs.getString("producer_id"));
    }

    private record Node(String id, String label, String sourceFile, String sourceLocation,
                        String module, String scope, String metadata, String producerId) {}
}
