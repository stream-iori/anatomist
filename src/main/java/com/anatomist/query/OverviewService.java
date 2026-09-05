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
        countByProducer(ov);
        ov.packageDeps = packageDeps();
        return ov;
    }

    public List<Map<String, Object>> packageDeps() {
        String sql = "SELECT src.package AS source_package, tgt.package AS target_package,"
                + "       e.relation, e.producer_id, COUNT(*) AS edge_count "
                + " FROM edges e "
                + " JOIN nodes src ON e.source_id = src.id "
                + " JOIN nodes tgt ON e.target_id = tgt.id "
                + " WHERE e.is_external = 0 "
                + resolver.selectorClause("src") + resolver.selectorClause("tgt")
                + "   AND src.package IS NOT NULL AND tgt.package IS NOT NULL "
                + "   AND src.package <> tgt.package "
                + "   AND e.relation IN (" + sqlIn(GraphConstants.PACKAGE_DEPENDENCY_RELATIONS) + ") "
                + " GROUP BY src.package, tgt.package, e.relation, e.producer_id "
                + " ORDER BY src.package, tgt.package, e.relation";
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String key = rs.getString(1) + "\u0000" + rs.getString(2) + "\u0000" + rs.getString(3);
                Map<String, Object> row = grouped.computeIfAbsent(key, ignored -> {
                    Map<String, Object> created = new LinkedHashMap<>();
                    created.put("source_package", get(rs, 1));
                    created.put("target_package", get(rs, 2));
                    created.put("relation", get(rs, 3));
                    created.put("edge_count", 0L);
                    created.put("producer_counts", new LinkedHashMap<String, Long>());
                    return created;
                });
                long count = rs.getLong(5);
                row.put("edge_count", ((Number) row.get("edge_count")).longValue() + count);
                @SuppressWarnings("unchecked") Map<String, Long> producers =
                        (Map<String, Long>) row.get("producer_counts");
                producers.merge(rs.getString(4), count, Long::sum);
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }
        return new ArrayList<>(grouped.values());
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
                + "AND e.producer_id<>'java-semantics' "
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
        queryList(conn, "SELECT package, kind, producer_id, COUNT(*) FROM nodes "
                + "n WHERE package IS NOT NULL " + resolver.selectorClause("n")
                + " GROUP BY package, kind, producer_id ORDER BY package", rs -> {
            String pkg = rs.getString(1);
            String kind = rs.getString(2);
            long count = rs.getLong(4);
            PackageStat stat = byPkg.computeIfAbsent(pkg, PackageStat::new);
            if (GraphConstants.TYPE_KINDS.contains(kind)) stat.types += count;
            else if (GraphConstants.METHOD_KINDS.contains(kind)) stat.methods += count;
            stat.producerCounts.merge(rs.getString(3), count, Long::sum);
            return null;
        });
        ov.packages.addAll(byPkg.values());
    }

    private void countByProducer(OverviewResult ov) {
        String sql = "SELECT producer_id,COUNT(*) FROM ("
                + "SELECT producer_id FROM nodes UNION ALL SELECT producer_id FROM edges WHERE producer_id<>'java-semantics' UNION ALL "
                + "SELECT producer_id FROM declarations UNION ALL SELECT producer_id FROM annotations UNION ALL "
                + "SELECT producer_id FROM semantic_annotations) GROUP BY producer_id ORDER BY producer_id";
        queryList(conn, sql, rs -> {
            ov.producerCounts.put(rs.getString(1), rs.getLong(2));
            return null;
        });
    }

    private static String get(ResultSet rows, int column) {
        try { return rows.getString(column); }
        catch (SQLException failure) { throw new RuntimeException(failure); }
    }
}
