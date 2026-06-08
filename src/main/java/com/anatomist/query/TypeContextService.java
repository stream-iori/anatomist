package com.anatomist.query;

import com.anatomist.json.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static com.anatomist.query.QueryInfra.*;

public class TypeContextService {

    private static final int MAX_DEPTH = CallGraphService.MAX_DEPTH;

    private final Connection conn;
    private final NodeResolver resolver;
    private final CallGraphService callGraph;

    public TypeContextService(Connection conn, NodeResolver resolver, CallGraphService callGraph) {
        this.conn = conn;
        this.resolver = resolver;
        this.callGraph = callGraph;
    }

    public ContextResult context(String fqnOrShorthand, int withCalleesDepth) {
        NodeRow node = resolver.resolveNodeRow(fqnOrShorthand);
        if (node == null) return null;

        ContextResult r = new ContextResult();
        r.node = node;

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + RowMappers.NODE_COLS
              + " FROM edges e JOIN nodes n ON e.target_id = n.id "
              + " WHERE e.source_id = ? AND e.relation = 'CONTAINS' "
              + " ORDER BY n.kind, n.source_location")) {
            ps.setString(1, node.id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) r.members.add(RowMappers.mapNode(rs));
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT annotation_fqn, attributes FROM annotations WHERE node_id = ?")) {
            ps.setString(1, node.id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("annotation_fqn", rs.getString(1));
                    String attrs = rs.getString(2);
                    if (attrs != null && !attrs.isEmpty()) {
                        try { row.put("attributes", Json.parseTree(attrs)); }
                        catch (Exception e) { row.put("attributes", attrs); }
                    }
                    r.annotations.add(row);
                }
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }

        if (withCalleesDepth > 0) {
            List<String> sources = new ArrayList<>();
            sources.add(node.id);
            for (NodeRow m : r.members) {
                if ("METHOD".equals(m.kind) || "CONSTRUCTOR".equals(m.kind)) sources.add(m.id);
            }
            r.callees = callGraph.callsFrom(sources, withCalleesDepth);
        }

        return r;
    }

    public HierarchyResult hierarchy(String typeRef) {
        List<String> seeds = resolver.resolveTypeIds(typeRef);
        HierarchyResult h = new HierarchyResult();
        if (seeds.isEmpty()) return h;

        String seed = seeds.get(0);
        NodeRow self = resolver.readNodeById(seed);
        if (self == null) return h;
        HierarchyResult.Entry s = new HierarchyResult.Entry();
        s.id = self.id; s.label = self.label; s.qualifiedName = self.qualifiedName;
        s.role = "self"; s.depth = 0;
        h.extendsChain.add(s);

        String sql = "WITH RECURSIVE chain(id, label, qname, depth) AS ("
                + "  SELECT n.id, n.label, n.qualified_name, 1 "
                + "    FROM edges e JOIN nodes n ON e.target_id = n.id "
                + "   WHERE e.source_id = ? AND e.relation = 'INHERITS' AND e.is_external = 0 "
                + "  UNION ALL "
                + "  SELECT n.id, n.label, n.qualified_name, c.depth + 1 "
                + "    FROM edges e JOIN nodes n ON e.target_id = n.id "
                + "    JOIN chain c ON e.source_id = c.id "
                + "   WHERE e.relation = 'INHERITS' AND e.is_external = 0 AND c.depth < ?"
                + ") SELECT id, label, qname, depth FROM chain ORDER BY depth";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, seed);
            ps.setInt(2, MAX_DEPTH);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    HierarchyResult.Entry e = new HierarchyResult.Entry();
                    e.id = rs.getString(1);
                    e.label = rs.getString(2);
                    e.qualifiedName = rs.getString(3);
                    e.role = "extends";
                    e.depth = rs.getInt(4);
                    h.extendsChain.add(e);
                }
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT external_target_fqn FROM edges "
              + " WHERE source_id = ? AND relation = 'INHERITS' AND is_external = 1")) {
            ps.setString(1, seed);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    HierarchyResult.Entry e = new HierarchyResult.Entry();
                    e.role = "extends";
                    e.depth = h.extendsChain.size();
                    e.isExternal = true;
                    e.externalTargetFqn = rs.getString(1);
                    e.qualifiedName = rs.getString(1);
                    e.label = shortName(rs.getString(1));
                    h.extendsChain.add(e);
                }
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT n.id, n.label, n.qualified_name, e.is_external, e.external_target_fqn "
              + " FROM edges e LEFT JOIN nodes n ON e.target_id = n.id "
              + " WHERE e.source_id = ? AND e.relation = 'IMPLEMENTS'")) {
            ps.setString(1, seed);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    HierarchyResult.Entry e = new HierarchyResult.Entry();
                    boolean external = rs.getInt(4) == 1;
                    e.role = "implements";
                    e.depth = 1;
                    if (external) {
                        e.isExternal = true;
                        e.externalTargetFqn = rs.getString(5);
                        e.qualifiedName = e.externalTargetFqn;
                        e.label = shortName(e.externalTargetFqn);
                    } else {
                        e.id = rs.getString(1);
                        e.label = rs.getString(2);
                        e.qualifiedName = rs.getString(3);
                    }
                    h.implementsList.add(e);
                }
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }
        return h;
    }
}
