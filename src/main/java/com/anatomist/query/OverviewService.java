package com.anatomist.query;

import com.anatomist.model.GraphConstants;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static com.anatomist.query.QueryInfra.*;

public class OverviewService {

    private final Connection conn;
    private final NodeResolver resolver;

    public OverviewService(Connection conn, NodeResolver resolver) {
        this.conn = conn;
        this.resolver = resolver;
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
                + resolver.selectorClause("src") + resolver.selectorClause("tgt")
                + "   AND src.package IS NOT NULL AND tgt.package IS NOT NULL "
                + "   AND src.package <> tgt.package "
                + "   AND e.relation IN (" + sqlIn(GraphConstants.PACKAGE_DEPENDENCY_RELATIONS) + ") "
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

    private void countByKind(OverviewResult ov) {
        queryList(conn, "SELECT kind, COUNT(*) FROM nodes n WHERE 1=1 "
                + resolver.selectorClause("n") + " GROUP BY kind ORDER BY kind", rs -> {
            ov.kindCounts.put(rs.getString(1), rs.getLong(2));
            return null;
        });
    }

    private void countEdgesByExternal(OverviewResult ov) {
        queryList(conn, "SELECT e.relation, e.is_external, COUNT(*) FROM edges e "
                + "JOIN nodes src ON e.source_id=src.id WHERE 1=1 "
                + resolver.selectorClause("src")
                + " GROUP BY e.relation, e.is_external ORDER BY e.relation", rs -> {
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
                + "n WHERE package IS NOT NULL " + resolver.selectorClause("n")
                + " GROUP BY package, kind ORDER BY package", rs -> {
            String pkg = rs.getString(1);
            String kind = rs.getString(2);
            long count = rs.getLong(3);
            PackageStat stat = byPkg.computeIfAbsent(pkg, PackageStat::new);
            if (GraphConstants.TYPE_KINDS.contains(kind)) stat.types += count;
            else if (GraphConstants.METHOD_KINDS.contains(kind)) stat.methods += count;
            return null;
        });
        ov.packages.addAll(byPkg.values());
    }
}
