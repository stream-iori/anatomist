package com.anatomist.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static com.anatomist.query.QueryInfra.*;

public class OverviewService {

    private static final Set<String> TYPE_KINDS = Set.of(
            "CLASS", "INTERFACE", "ENUM", "ANNOTATION", "RECORD", "ANONYMOUS_CLASS");

    private final Connection conn;

    public OverviewService(Connection conn) {
        this.conn = conn;
    }

    public OverviewResult overview() {
        OverviewResult ov = new OverviewResult();
        countByKind(ov);
        countEdgesByExternal(ov);
        tallyPackages(ov);
        ov.packageDeps = packageDeps();
        return ov;
    }

    public List<Map<String, Object>> packageDeps() {
        String sql = "SELECT src.package AS source_package, tgt.package AS target_package,"
                + "       e.relation, COUNT(*) AS edge_count "
                + " FROM edges e "
                + " JOIN nodes src ON e.source_id = src.id "
                + " JOIN nodes tgt ON e.target_id = tgt.id "
                + " WHERE e.is_external = 0 "
                + "   AND src.package IS NOT NULL AND tgt.package IS NOT NULL "
                + "   AND src.package <> tgt.package "
                + "   AND e.relation IN ('CALLS','REFERENCES','IMPLEMENTS','INHERITS') "
                + " GROUP BY src.package, tgt.package, e.relation "
                + " ORDER BY src.package, tgt.package, e.relation";
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source_package", rs.getString(1));
                row.put("target_package", rs.getString(2));
                row.put("relation", rs.getString(3));
                row.put("edge_count", rs.getInt(4));
                out.add(row);
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }
        return out;
    }

    public List<ClassEdge> classDepsInternal(int maxEdges) {
        String sql =
                "WITH RECURSIVE owner(node_id, cur_id, cur_kind) AS ("
              + "  SELECT id, id, kind FROM nodes "
              + "  UNION ALL "
              + "  SELECT o.node_id, c.source_id, p.kind "
              + "  FROM owner o "
              + "  JOIN edges c INDEXED BY idx_edges_target_relation "
              + "       ON c.target_id = o.cur_id AND c.relation = 'CONTAINS' AND c.is_external = 0 "
              + "  JOIN nodes p ON p.id = c.source_id "
              + "  WHERE o.cur_kind NOT IN ('CLASS','INTERFACE','ENUM','ANNOTATION','RECORD') "
              + "), type_of AS ("
              + "  SELECT node_id, cur_id AS type_id FROM owner "
              + "  WHERE cur_kind IN ('CLASS','INTERFACE','ENUM','ANNOTATION','RECORD') "
              + ") "
              + "SELECT st.id AS source, st.label AS source_label, st.package AS source_package, "
              + "       st.kind AS source_kind, json_extract(st.metadata,'$.isAbstract') AS source_abstract, "
              + "       tt.id AS target, tt.label AS target_label, tt.package AS target_package, "
              + "       tt.kind AS target_kind, json_extract(tt.metadata,'$.isAbstract') AS target_abstract, "
              + "       MAX(CASE WHEN e.relation IN ('IMPLEMENTS','INHERITS') THEN 1 ELSE 0 END) AS is_inherit, "
              + "       COUNT(*) AS edge_count "
              + "FROM edges e "
              + "JOIN type_of so ON e.source_id = so.node_id "
              + "JOIN type_of ot ON e.target_id = ot.node_id "
              + "JOIN nodes st ON so.type_id = st.id "
              + "JOIN nodes tt ON ot.type_id = tt.id "
              + "WHERE e.is_external = 0 "
              + "  AND e.relation IN ('CALLS','REFERENCES','WIRES','IMPLEMENTS','INHERITS') "
              + "  AND so.type_id <> ot.type_id "
              + "GROUP BY st.id, tt.id "
              + "ORDER BY edge_count DESC, source, target";
        List<ClassEdge> all = queryList(conn, sql, rs -> new ClassEdge(
                rs.getString("source"),
                rs.getString("source_label"),
                rs.getString("source_package"),
                rs.getString("source_kind"),
                readBool(rs, "source_abstract"),
                rs.getString("target"),
                rs.getString("target_label"),
                rs.getString("target_package"),
                rs.getString("target_kind"),
                readBool(rs, "target_abstract"),
                readBool(rs, "is_inherit"),
                rs.getInt("edge_count")));
        return (maxEdges > 0 && all.size() > maxEdges) ? all.subList(0, maxEdges) : all;
    }

    private void countByKind(OverviewResult ov) {
        queryList(conn, "SELECT kind, COUNT(*) FROM nodes GROUP BY kind ORDER BY kind", rs -> {
            ov.kindCounts.put(rs.getString(1), rs.getLong(2));
            return null;
        });
    }

    private void countEdgesByExternal(OverviewResult ov) {
        queryList(conn, "SELECT relation, is_external, COUNT(*) FROM edges "
                + "GROUP BY relation, is_external ORDER BY relation", rs -> {
            String rel = rs.getString(1);
            long count = rs.getLong(3);
            if (rs.getInt(2) == 1) ov.externalEdgeCounts.merge(rel, count, Long::sum);
            else ov.internalEdgeCounts.merge(rel, count, Long::sum);
            return null;
        });
    }

    private void tallyPackages(OverviewResult ov) {
        Map<String, PackageStat> byPkg = new LinkedHashMap<>();
        queryList(conn, "SELECT package, kind, COUNT(*) FROM nodes "
                + "WHERE package IS NOT NULL GROUP BY package, kind ORDER BY package", rs -> {
            String pkg = rs.getString(1);
            String kind = rs.getString(2);
            long count = rs.getLong(3);
            PackageStat stat = byPkg.computeIfAbsent(pkg, PackageStat::new);
            if (TYPE_KINDS.contains(kind)) stat.types += count;
            else if ("METHOD".equals(kind) || "CONSTRUCTOR".equals(kind)) stat.methods += count;
            return null;
        });
        ov.packages.addAll(byPkg.values());
    }
}
