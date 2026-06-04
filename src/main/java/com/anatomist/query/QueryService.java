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
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only query API over a previously-built anatomist SQLite index.
 *
 * <p>All methods are pure SQL + FTS5 + recursive CTE. Never invokes the
 * JavaParser pipeline. See {@code docs/scenario-2-query.md} for command
 * semantics and SQL templates.</p>
 *
 * <p>Thread-safety: each instance owns one {@link Connection}; not safe for
 * concurrent use. Construct one per query invocation.</p>
 */
public class QueryService implements AutoCloseable {

    /** SQLite hard-cap on recursion depth to avoid runaway CTEs. */
    public static final int MAX_DEPTH = 20;

    private static final Set<String> TYPE_KINDS = Set.of(
            "CLASS", "INTERFACE", "ENUM", "ANNOTATION", "RECORD", "ANONYMOUS_CLASS");

    private final Connection conn;
    private final NodeResolver resolver;

    public QueryService(Path dbPath) {
        try {
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open index db: " + dbPath, e);
        }
        this.resolver = new NodeResolver(conn);
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }

    // ──────────────────────────────────────────────────────────────────
    // B. 符号定位
    // ──────────────────────────────────────────────────────────────────

    /** B1/B2: FTS5 search on node names, optionally filtered by kind. */
    public List<NodeRow> search(String term, String kind, int limit) {
        // FTS5 MATCH is column-scoped via {label}: term. Plain term searches all columns.
        String ftsExpr = term == null ? "" : term.trim();
        if (ftsExpr.isEmpty()) return Collections.emptyList();
        // Suffix-* wildcard if no operators present, so `Order` matches `OrderService`.
        if (!ftsExpr.matches(".*[\\s\"():*-].*")) ftsExpr = ftsExpr + "*";

        StringBuilder sql = new StringBuilder()
                .append("SELECT ").append(RowMappers.NODE_COLS).append(" ")
                .append("FROM node_names nn ")
                .append("JOIN nodes n ON nn.rowid = n.rowid ")
                .append("WHERE node_names MATCH ? ");
        List<Object> args = new ArrayList<>();
        args.add(ftsExpr);
        if (kind != null && !kind.isEmpty()) {
            sql.append("AND n.kind = ? ");
            args.add(kind);
        }
        sql.append("ORDER BY rank LIMIT ?");
        args.add(limit > 0 ? limit : 20);
        return runNodeQuery(sql.toString(), args);
    }

    /** B4: search by annotation FQN substring. */
    public List<NodeRow> searchByAnnotation(String annotationTerm, String kind, int limit) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT DISTINCT ").append(RowMappers.NODE_COLS).append(" ")
                .append("FROM nodes n JOIN annotations a ON n.id = a.node_id ")
                .append("WHERE a.annotation_fqn LIKE ? ");
        String like = "%" + annotationTerm.replace("@", "") + "%";
        List<Object> args = new ArrayList<>();
        args.add(like);
        if (kind != null && !kind.isEmpty()) {
            sql.append("AND n.kind = ? ");
            args.add(kind);
        }
        sql.append("ORDER BY n.qualified_name LIMIT ?");
        args.add(limit > 0 ? limit : 50);
        return runNodeQuery(sql.toString(), args);
    }

    /** B5/F2: classes that implement (or extend) the given interface FQN. */
    public List<NodeRow> implementorsOf(String typeRef) {
        List<String> targetIds = resolveTypeIds(typeRef);
        if (targetIds.isEmpty()) return Collections.emptyList();

        String placeholders = qmarks(targetIds.size());
        String sql = "SELECT " + RowMappers.NODE_COLS
                + " FROM edges e JOIN nodes n ON e.source_id = n.id "
                + " WHERE e.relation IN ('IMPLEMENTS','INHERITS') "
                + "   AND e.is_external = 0 AND e.target_id IN (" + placeholders + ") "
                + " ORDER BY n.qualified_name";
        return runNodeQuery(sql, new ArrayList<>(targetIds));
    }

    // ──────────────────────────────────────────────────────────────────
    // C. 结构理解
    // ──────────────────────────────────────────────────────────────────

    /** C1/C3: full context of a type or method. */
    public ContextResult context(String fqnOrShorthand, int withCalleesDepth) {
        NodeRow node = resolveNodeRow(fqnOrShorthand);
        if (node == null) return null;

        ContextResult r = new ContextResult();
        r.node = node;

        // CONTAINS edges → members
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

        // annotations
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
            // Aggregate callees out of every contained method as well as the
            // node itself (when the target is a method).
            List<String> sources = new ArrayList<>();
            sources.add(node.id);
            for (NodeRow m : r.members) {
                if ("METHOD".equals(m.kind) || "CONSTRUCTOR".equals(m.kind)) sources.add(m.id);
            }
            r.callees = callsFrom(sources, withCalleesDepth);
        }

        return r;
    }

    /** C2: extends chain (recursive) + direct implements list. */
    public HierarchyResult hierarchy(String typeRef) {
        List<String> seeds = resolveTypeIds(typeRef);
        HierarchyResult h = new HierarchyResult();
        if (seeds.isEmpty()) return h;

        // self entry
        String seed = seeds.get(0);
        NodeRow self = readNodeById(seed);
        if (self == null) return h;
        HierarchyResult.Entry s = new HierarchyResult.Entry();
        s.id = self.id; s.label = self.label; s.qualifiedName = self.qualifiedName;
        s.role = "self"; s.depth = 0;
        h.extendsChain.add(s);

        // recursive walk on INHERITS (internal only). External targets stop the chain
        // but are still surfaced.
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

        // external INHERITS leaf
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

        // direct IMPLEMENTS
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

    // ──────────────────────────────────────────────────────────────────
    // D. 调用链追踪
    // ──────────────────────────────────────────────────────────────────

    /** D1/D3: outgoing CALLS, recursive when depth > 1. */
    public List<EdgeRow> calleesOf(String methodRef, int depth) {
        return callsFrom(resolveMethodIds(methodRef), Math.max(1, depth));
    }

    /** D2/F1: incoming CALLS, recursive when depth > 1. */
    public List<EdgeRow> callersOf(String methodRef, int depth) {
        return callsTo(resolveMethodIds(methodRef), Math.max(1, depth));
    }

    private List<EdgeRow> callsFrom(List<String> seedIds, int depth) {
        if (seedIds.isEmpty()) return Collections.emptyList();
        String placeholders = qmarks(seedIds.size());
        String sql = "WITH RECURSIVE chain AS ("
                + "  SELECT " + RowMappers.CHAIN_CTE_COLS + ", 1 AS depth"
                + "    FROM edges e "
                + "   WHERE e.source_id IN (" + placeholders + ") AND e.relation = 'CALLS' "
                + "  UNION ALL "
                + "  SELECT " + RowMappers.CHAIN_CTE_COLS + ", c.depth + 1"
                + "    FROM edges e JOIN chain c ON e.source_id = c.target_id "
                + "   WHERE e.relation = 'CALLS' AND c.depth < ? AND c.is_external = 0 "
                + ") SELECT " + RowMappers.EDGE_COLS_CHAIN
                + "    FROM chain c "
                + "    LEFT JOIN nodes src ON c.source_id = src.id "
                + "    LEFT JOIN nodes tgt ON c.target_id = tgt.id "
                + "   ORDER BY c.depth, c.source_id, c.target_id";
        List<Object> args = new ArrayList<>(seedIds);
        args.add(depth);
        return dedup(runEdgeQuery(sql, args));
    }

    private List<EdgeRow> callsTo(List<String> seedIds, int depth) {
        if (seedIds.isEmpty()) return Collections.emptyList();
        String placeholders = qmarks(seedIds.size());
        String sql = "WITH RECURSIVE chain AS ("
                + "  SELECT " + RowMappers.CHAIN_CTE_COLS + ", 1 AS depth"
                + "    FROM edges e "
                + "   WHERE e.target_id IN (" + placeholders + ") AND e.relation = 'CALLS' AND e.is_external = 0 "
                + "  UNION ALL "
                + "  SELECT " + RowMappers.CHAIN_CTE_COLS + ", c.depth + 1"
                + "    FROM edges e JOIN chain c ON e.target_id = c.source_id "
                + "   WHERE e.relation = 'CALLS' AND e.is_external = 0 AND c.depth < ? "
                + ") SELECT " + RowMappers.EDGE_COLS_CHAIN
                + "    FROM chain c "
                + "    LEFT JOIN nodes src ON c.source_id = src.id "
                + "    LEFT JOIN nodes tgt ON c.target_id = tgt.id "
                + "   ORDER BY c.depth, c.source_id, c.target_id";
        List<Object> args = new ArrayList<>(seedIds);
        args.add(depth);
        return dedup(runEdgeQuery(sql, args));
    }

    // ──────────────────────────────────────────────────────────────────
    // C4/C5 + F3: deps-of / used-by
    // ──────────────────────────────────────────────────────────────────

    /** C4: outgoing CALLS + REFERENCES from a type (and contained methods). */
    public List<EdgeRow> depsOf(String typeRef) {
        List<String> sources = expandTypeToMembers(typeRef);
        if (sources.isEmpty()) return Collections.emptyList();
        String ph = qmarks(sources.size());
        String sql = "SELECT " + RowMappers.edgeColsFlat("1")
                + RowMappers.EDGE_FROM_JOINS
                + " WHERE e.source_id IN (" + ph + ") "
                + "   AND e.relation IN ('CALLS','REFERENCES','WIRES') "
                + " ORDER BY e.relation, e.source_id";
        return runEdgeQuery(sql, new ArrayList<>(sources));
    }

    /** C5/F3: incoming CALLS + REFERENCES to a type (and contained methods). */
    public List<EdgeRow> usedBy(String typeRef) {
        List<String> targets = expandTypeToMembers(typeRef);
        if (targets.isEmpty()) return Collections.emptyList();
        String ph = qmarks(targets.size());
        String sql = "SELECT " + RowMappers.edgeColsFlat("1")
                + RowMappers.EDGE_FROM_JOINS
                + " WHERE e.target_id IN (" + ph + ") "
                + "   AND e.is_external = 0 "
                + "   AND e.relation IN ('CALLS','REFERENCES','WIRES') "
                + " ORDER BY e.relation, e.source_id";
        return runEdgeQuery(sql, new ArrayList<>(targets));
    }

    private List<String> expandTypeToMembers(String typeRef) {
        List<String> typeIds = resolveTypeIds(typeRef);
        if (typeIds.isEmpty()) return Collections.emptyList();
        List<String> all = new ArrayList<>(typeIds);
        String ph = qmarks(typeIds.size());
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT target_id FROM edges WHERE source_id IN (" + ph + ") "
              + "  AND relation = 'CONTAINS' AND is_external = 0")) {
            for (int i = 0; i < typeIds.size(); i++) ps.setString(i + 1, typeIds.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) all.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }
        return all;
    }

    // ──────────────────────────────────────────────────────────────────
    // Field-level / path / package aggregations (DESIGN.md §CLI 命令)
    // ──────────────────────────────────────────────────────────────────

    /** Who READS this field. {@code fieldRef} accepts {@code pkg.Class#name}
     *  or {@code Class.name}; bare names match by label. */
    public List<EdgeRow> fieldReaders(String fieldRef) {
        return fieldEdgeQuery(fieldRef, "READS");
    }

    /** Who WRITES this field. */
    public List<EdgeRow> fieldWriters(String fieldRef) {
        return fieldEdgeQuery(fieldRef, "WRITES");
    }

    private List<EdgeRow> fieldEdgeQuery(String fieldRef, String relation) {
        List<String> fieldIds = resolveFieldIds(fieldRef);
        if (fieldIds.isEmpty()) return Collections.emptyList();
        String ph = qmarks(fieldIds.size());
        String sql = "SELECT " + RowMappers.edgeColsFlat("1")
                + RowMappers.EDGE_FROM_JOINS
                + " WHERE e.target_id IN (" + ph + ") AND e.relation = ? AND e.is_external = 0 "
                + " ORDER BY e.source_id, e.source_location";
        List<Object> args = new ArrayList<>(fieldIds);
        args.add(relation);
        return runEdgeQuery(sql, args);
    }

    private List<String> resolveFieldIds(String input) {
        return resolver.resolveFieldIds(input);
    }

    /** Bidirectional BFS via two recursive CTEs from each end, then intersect.
     *  Returns the shortest CALLS chain from {@code fromMethodRef} to
     *  {@code toMethodRef} as an ordered list of edges (depth = position in chain).
     *  Empty list when unreachable within {@link #MAX_DEPTH} hops total. */
    public List<EdgeRow> callPath(String fromMethodRef, String toMethodRef, int maxDepth) {
        List<String> froms = resolveMethodIds(fromMethodRef);
        List<String> tos   = resolveMethodIds(toMethodRef);
        if (froms.isEmpty() || tos.isEmpty()) return Collections.emptyList();
        int depthCap = Math.min(maxDepth > 0 ? maxDepth : 5, MAX_DEPTH);

        // BFS forward from `froms` recording (node, depth, parent). Stop as soon
        // as a `to` node appears.
        Map<String, String> parent = new LinkedHashMap<>(); // child -> parent
        Map<String, Integer> depth = new HashMap<>();
        Deque<String> frontier = new ArrayDeque<>();
        for (String f : froms) { depth.put(f, 0); frontier.add(f); }

        String hit = null;
        Set<String> toSet = new HashSet<>(tos);
        while (!frontier.isEmpty() && hit == null) {
            String cur = frontier.pollFirst();
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
                        if (toSet.contains(next)) { hit = next; break; }
                        frontier.addLast(next);
                    }
                }
            } catch (SQLException e) {
                throw rethrow(e);
            }
        }
        if (hit == null) return Collections.emptyList();

        // Walk parent chain back to a `from` seed, then materialize as EdgeRows.
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
        return rows;
    }

    /** Aggregated package-level dependency view. For every (source_pkg → target_pkg)
     *  pair seen via CALLS / REFERENCES / IMPLEMENTS / INHERITS (project-internal only),
     *  return the edge count. Self-package edges are excluded. */
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

    // ──────────────────────────────────────────────────────────────────
    // Overview (top-down project summary)
    // ──────────────────────────────────────────────────────────────────

    /** Project-wide summary: node-kind counts, edge-relation counts (internal /
     *  external), per-package type/method tallies, and the package dependency
     *  skeleton (reusing {@link #packageDeps()}). */
    public OverviewResult overview() {
        OverviewResult ov = new OverviewResult();
        countByKind(ov);
        countEdgesByExternal(ov);
        tallyPackages(ov);
        ov.packageDeps = packageDeps();
        return ov;
    }

    private void countByKind(OverviewResult ov) {
        queryList("SELECT kind, COUNT(*) FROM nodes GROUP BY kind ORDER BY kind", rs -> {
            ov.kindCounts.put(rs.getString(1), rs.getLong(2));
            return null;
        });
    }

    private void countEdgesByExternal(OverviewResult ov) {
        queryList("SELECT relation, is_external, COUNT(*) FROM edges "
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
        queryList("SELECT package, kind, COUNT(*) FROM nodes "
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

    /** Class-to-class internal edges, annotated with each endpoint's package,
     *  kind and abstractness, for the HTML export drill-down. Aggregates CALLS /
     *  REFERENCES / WIRES / IMPLEMENTS / INHERITS to the declaring type of each
     *  endpoint (method/field edges roll up to their owning class via
     *  {@code CONTAINS}). When {@code maxEdges > 0} the result is capped at that
     *  many rows (highest edge_count first). */
    public List<ClassEdge> classDepsInternal(int maxEdges) {
        // Roll any node up to its nearest *named* enclosing type by walking
        // CONTAINS edges upward. A single level isn't enough: lambdas, method
        // references and anonymous classes are CONTAINED by a method, so they'd
        // otherwise stop at the method instead of the class. ANONYMOUS_CLASS is
        // deliberately NOT a stop kind — anon bodies fold into the named class.
        // CONTAINS forms a tree, so the recursion terminates.
        //
        // INDEXED BY idx_edges_target_relation is load-bearing on large indexes:
        // without it SQLite picks idx_edges_relation_external_fqn (relation,
        // is_external) for the recursive join, which matches *every* CONTAINS
        // edge per step instead of looking up by target_id — turning a ~1s query
        // into a multi-minute scan on a ~45k-node project (imerchantsettle).
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
        List<ClassEdge> all = queryList(sql, rs -> new ClassEdge(
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

    // ──────────────────────────────────────────────────────────────────
    // Enrich (aggregate views for Agent consumption)
    // ──────────────────────────────────────────────────────────────────

    /** Aggregate node-level enrich result: node + members + annotations +
     *  semantic annotations + callees (depth-bounded) + related docs +
     *  suggested follow-up queries. */
    public EnrichResult enrichNode(String fqnOrShorthand, int depth, boolean withDocs) {
        NodeRow node = resolveNodeRow(fqnOrShorthand);
        if (node == null) return null;

        EnrichResult r = new EnrichResult();
        r.node = node;

        // Reuse context() to collect members + annotations (and optional callees).
        int effectiveDepth = Math.max(0, depth);
        ContextResult ctx = context(fqnOrShorthand, effectiveDepth);
        if (ctx != null) {
            r.members = ctx.members;
            r.annotations = ctx.annotations;
            if (ctx.callees != null) r.callees = ctx.callees;
        }

        r.semanticAnnotations = readSemanticAnnotations(node.id);
        if (withDocs) {
            r.relatedDocs = searchRelatedDocs(node.label, node.qualifiedName);
        }
        r.suggestedQueries = suggestQueries(r);
        return r;
    }

    public EnrichResult enrichPackage(String pkg, boolean withDocs) {
        if (pkg == null || pkg.isEmpty()) return null;

        // Verify there's at least one node in this package.
        List<NodeRow> types = runNodeQuery(
                "SELECT " + RowMappers.NODE_COLS
              + "  FROM nodes n WHERE package = ? AND kind IN ("
              + qmarks(TYPE_KINDS.size()) + ") "
              + " ORDER BY label",
                concat(List.of(pkg), new ArrayList<>(TYPE_KINDS)));
        if (types.isEmpty()) return null;

        EnrichResult r = new EnrichResult();
        r.pkg = pkg;
        r.members = types;

        // Aggregate semantic annotations across all type nodes in the package.
        for (NodeRow t : types) {
            r.semanticAnnotations.addAll(readSemanticAnnotations(t.id));
        }

        // Package deps: filter packageDeps() rows where source_package == pkg.
        for (Map<String, Object> row : packageDeps()) {
            if (pkg.equals(row.get("source_package"))) {
                r.packageDeps.add(row);
            }
        }

        if (withDocs) {
            r.relatedDocs = searchRelatedDocs(shortName(pkg), pkg);
        }
        r.suggestedQueries = suggestQueries(r);
        return r;
    }

    public List<SemanticAnnotationRow> readSemanticAnnotations(String nodeId) {
        if (nodeId == null) return new ArrayList<>();
        List<SemanticAnnotationRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT category, business_label, business_description, domain_context, source, confidence"
              + " FROM semantic_annotations WHERE node_id = ? "
              + " ORDER BY category, source")) {
            ps.setString(1, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SemanticAnnotationRow row = new SemanticAnnotationRow();
                    row.category = rs.getString(1);
                    row.businessLabel = rs.getString(2);
                    row.businessDescription = rs.getString(3);
                    row.domainContext = rs.getString(4);
                    row.source = rs.getString(5);
                    row.confidence = rs.getString(6);
                    out.add(row);
                }
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }
        return out;
    }

    public List<DocSnippet> searchRelatedDocs(String label, String qualifiedName) {
        List<DocSnippet> out = new ArrayList<>();
        // Skip if FTS5 documents table is empty or hasn't been populated.
        if ((label == null || label.isEmpty()) && (qualifiedName == null || qualifiedName.isEmpty())) {
            return out;
        }
        // Prefer label match; fall back to qualified_name. Cap at 3 per node.
        String term = label != null && !label.isEmpty() ? label : qualifiedName;
        // FTS5 chokes on dotted strings; tokenize qualifiedName via short name.
        if (term != null && term.contains(".")) term = shortName(term);
        if (term == null || term.isEmpty()) return out;

        String sql = "SELECT d.path, d.title, d.content, d.doc_type "
                + " FROM doc_content dc JOIN documents d ON dc.rowid = d.id "
                + " WHERE doc_content MATCH ? ORDER BY rank LIMIT 3";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, term);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DocSnippet snip = new DocSnippet();
                    snip.path = rs.getString(1);
                    snip.title = rs.getString(2);
                    String content = rs.getString(3);
                    snip.snippet = content == null ? null
                            : (content.length() > 200 ? content.substring(0, 200) + "…" : content);
                    snip.docType = rs.getString(4);
                    out.add(snip);
                }
            }
        } catch (SQLException e) {
            // doc_content may not exist if schema wasn't fully migrated; degrade silently.
            return out;
        }
        return out;
    }

    public List<String> suggestQueries(EnrichResult r) {
        List<String> out = new ArrayList<>();
        if (r == null) return out;
        if (r.pkg != null) {
            out.add("anatomist package-deps --index <db>");
            out.add("anatomist search <term> --kind CLASS --index <db>");
            return out;
        }
        if (r.node == null) return out;
        String q = r.node.qualifiedName;
        String kind = r.node.kind;
        if ("METHOD".equals(kind) || "CONSTRUCTOR".equals(kind)) {
            out.add("anatomist callers-of " + q);
            out.add("anatomist callees-of " + q + " --depth 3");
        } else if (TYPE_KINDS.contains(kind)) {
            out.add("anatomist callers-of " + q);
            out.add("anatomist callees-of " + q + " --depth 2");
            out.add("anatomist deps-of " + q);
            out.add("anatomist used-by " + q);
            out.add("anatomist hierarchy " + q);
        }
        boolean hasBusinessService = r.semanticAnnotations.stream()
                .anyMatch(sa -> "BUSINESS_SERVICE".equals(sa.category));
        if (hasBusinessService) {
            out.add("anatomist implementors-of " + q);
        }
        return out;
    }

    private static <T> List<Object> concat(List<?> a, List<?> b) {
        List<Object> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    // ──────────────────────────────────────────────────────────────────
    // FQN resolution (delegated to NodeResolver)
    // ──────────────────────────────────────────────────────────────────

    public List<String> resolveTypeIds(String input) {
        return resolver.resolveTypeIds(input);
    }

    public List<String> resolveMethodIds(String input) {
        return resolver.resolveMethodIds(input);
    }

    /** Resolve to a single NodeRow when caller wants one row (e.g. context). */
    public NodeRow resolveNodeRow(String input) {
        return resolver.resolveNodeRow(input);
    }

    // ──────────────────────────────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────────────────────────────

    private NodeRow readNodeById(String id) {
        return resolver.readNodeById(id);
    }

    private List<NodeRow> runNodeQuery(String sql, List<Object> args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<NodeRow> out = new ArrayList<>();
                while (rs.next()) out.add(RowMappers.mapNode(rs));
                return out;
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }
    }

    private List<EdgeRow> runEdgeQuery(String sql, List<Object> args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<EdgeRow> out = new ArrayList<>();
                while (rs.next()) out.add(RowMappers.mapEdge(rs));
                return out;
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }
    }

    private static void bind(PreparedStatement ps, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            Object v = args.get(i);
            if (v instanceof Integer iv) ps.setInt(i + 1, iv);
            else ps.setString(i + 1, v == null ? null : v.toString());
        }
    }

    private static String qmarks(int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ",?");
        return sb.toString();
    }

    private static String shortName(String fqn) {
        if (fqn == null) return null;
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }

    /** De-dupe edges by (source, target, depth) — recursive CTE on a graph with
     *  fan-in produces duplicates at the same depth. */
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

    private static RuntimeException rethrow(SQLException e) {
        return new RuntimeException("query failed: " + e.getMessage(), e);
    }

    /** Maps the current {@link ResultSet} row to a {@code T}. */
    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    /** Run a parameterless query and map every row, centralizing the
     *  prepare / iterate / {@code catch (SQLException) -> rethrow} boilerplate
     *  that otherwise repeats at every call site. */
    private <T> List<T> queryList(String sql, RowMapper<T> mapper) {
        List<T> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(mapper.map(rs));
        } catch (SQLException e) {
            throw rethrow(e);
        }
        return out;
    }

    /** SQLite has no native boolean: an absent JSON field
     *  ({@code json_extract} -> NULL -> getInt 0) and an explicit {@code false}
     *  both read as 0. Only an explicit 1 is true. */
    private static boolean readBool(ResultSet rs, String column) throws SQLException {
        return rs.getInt(column) == 1;
    }
}
