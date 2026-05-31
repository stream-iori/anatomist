package com.anatomist.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private final ObjectMapper json = JsonFormatter.mapper();

    public QueryService(Path dbPath) {
        try {
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open index db: " + dbPath, e);
        }
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
                .append("SELECT n.id, n.label, n.kind, n.qualified_name, n.source_file,")
                .append("       n.source_location, n.module ")
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
                .append("SELECT DISTINCT n.id, n.label, n.kind, n.qualified_name, n.source_file,")
                .append("       n.source_location, n.module ")
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
        String sql = "SELECT n.id, n.label, n.kind, n.qualified_name, n.source_file,"
                + "        n.source_location, n.module "
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
                "SELECT n.id, n.label, n.kind, n.qualified_name, n.source_file,"
              + "        n.source_location, n.module "
              + " FROM edges e JOIN nodes n ON e.target_id = n.id "
              + " WHERE e.source_id = ? AND e.relation = 'CONTAINS' "
              + " ORDER BY n.kind, n.source_location")) {
            ps.setString(1, node.id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) r.members.add(readNodeRow(rs));
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
                        try { row.put("attributes", json.readValue(attrs, JsonNode.class)); }
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
                + "  SELECT e.source_id, e.target_id, e.external_target_fqn, e.relation,"
                + "         e.call_kind, e.is_external, e.source_file, e.source_location, 1 AS depth"
                + "    FROM edges e "
                + "   WHERE e.source_id IN (" + placeholders + ") AND e.relation = 'CALLS' "
                + "  UNION ALL "
                + "  SELECT e.source_id, e.target_id, e.external_target_fqn, e.relation,"
                + "         e.call_kind, e.is_external, e.source_file, e.source_location, c.depth + 1"
                + "    FROM edges e JOIN chain c ON e.source_id = c.target_id "
                + "   WHERE e.relation = 'CALLS' AND c.depth < ? AND c.is_external = 0 "
                + ") SELECT c.source_id, c.target_id, c.external_target_fqn, c.relation, c.call_kind,"
                + "         c.is_external, c.source_file, c.source_location, c.depth,"
                + "         src.label AS src_label, tgt.label AS tgt_label, tgt.qualified_name AS tgt_q"
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
                + "  SELECT e.source_id, e.target_id, e.external_target_fqn, e.relation,"
                + "         e.call_kind, e.is_external, e.source_file, e.source_location, 1 AS depth"
                + "    FROM edges e "
                + "   WHERE e.target_id IN (" + placeholders + ") AND e.relation = 'CALLS' AND e.is_external = 0 "
                + "  UNION ALL "
                + "  SELECT e.source_id, e.target_id, e.external_target_fqn, e.relation,"
                + "         e.call_kind, e.is_external, e.source_file, e.source_location, c.depth + 1"
                + "    FROM edges e JOIN chain c ON e.target_id = c.source_id "
                + "   WHERE e.relation = 'CALLS' AND e.is_external = 0 AND c.depth < ? "
                + ") SELECT c.source_id, c.target_id, c.external_target_fqn, c.relation, c.call_kind,"
                + "         c.is_external, c.source_file, c.source_location, c.depth,"
                + "         src.label AS src_label, tgt.label AS tgt_label, tgt.qualified_name AS tgt_q"
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
        String sql = "SELECT e.source_id, e.target_id, e.external_target_fqn, e.relation, e.call_kind,"
                + "         e.is_external, e.source_file, e.source_location, 1 AS depth,"
                + "         src.label, tgt.label, tgt.qualified_name "
                + " FROM edges e "
                + " LEFT JOIN nodes src ON e.source_id = src.id "
                + " LEFT JOIN nodes tgt ON e.target_id = tgt.id "
                + " WHERE e.source_id IN (" + ph + ") "
                + "   AND e.relation IN ('CALLS','REFERENCES') "
                + " ORDER BY e.relation, e.source_id";
        return runEdgeQuery(sql, new ArrayList<>(sources));
    }

    /** C5/F3: incoming CALLS + REFERENCES to a type (and contained methods). */
    public List<EdgeRow> usedBy(String typeRef) {
        List<String> targets = expandTypeToMembers(typeRef);
        if (targets.isEmpty()) return Collections.emptyList();
        String ph = qmarks(targets.size());
        String sql = "SELECT e.source_id, e.target_id, e.external_target_fqn, e.relation, e.call_kind,"
                + "         e.is_external, e.source_file, e.source_location, 1 AS depth,"
                + "         src.label, tgt.label, tgt.qualified_name "
                + " FROM edges e "
                + " LEFT JOIN nodes src ON e.source_id = src.id "
                + " LEFT JOIN nodes tgt ON e.target_id = tgt.id "
                + " WHERE e.target_id IN (" + ph + ") "
                + "   AND e.is_external = 0 "
                + "   AND e.relation IN ('CALLS','REFERENCES') "
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
        String sql = "SELECT e.source_id, e.target_id, e.external_target_fqn, e.relation, e.call_kind,"
                + "         e.is_external, e.source_file, e.source_location, 1 AS depth,"
                + "         src.label, tgt.label, tgt.qualified_name "
                + " FROM edges e "
                + " LEFT JOIN nodes src ON e.source_id = src.id "
                + " LEFT JOIN nodes tgt ON e.target_id = tgt.id "
                + " WHERE e.target_id IN (" + ph + ") AND e.relation = ? AND e.is_external = 0 "
                + " ORDER BY e.source_id, e.source_location";
        List<Object> args = new ArrayList<>(fieldIds);
        args.add(relation);
        return runEdgeQuery(sql, args);
    }

    private List<String> resolveFieldIds(String input) {
        if (input == null || input.isEmpty()) return Collections.emptyList();
        // pkg.Class#field — exact id (FIELD id = <classFqn>#<name>, no parens)
        if (input.contains("#")) {
            return runStringColumn(
                    "SELECT id FROM nodes WHERE kind='FIELD' AND id = ? ORDER BY id",
                    List.of(input));
        }
        // Class.field — split at last dot; if typePart is qualified, exact match
        int dot = input.lastIndexOf('.');
        if (dot > 0) {
            String typePart = input.substring(0, dot);
            String fname = input.substring(dot + 1);
            if (typePart.contains(".")) {
                return runStringColumn(
                        "SELECT id FROM nodes WHERE kind='FIELD' AND id = ? ORDER BY id",
                        List.of(typePart + "#" + fname));
            }
            return runStringColumn(
                    "SELECT id FROM nodes WHERE kind='FIELD' AND id LIKE ? ORDER BY id",
                    List.of("%." + typePart + "#" + fname));
        }
        // bare name — by label
        return runStringColumn(
                "SELECT id FROM nodes WHERE kind='FIELD' AND label = ? ORDER BY qualified_name",
                List.of(input));
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
                    "SELECT e.source_id, e.target_id, e.external_target_fqn, e.relation, e.call_kind,"
                  + "         e.is_external, e.source_file, e.source_location, ? AS depth,"
                  + "         src.label, tgt.label, tgt.qualified_name "
                  + " FROM edges e "
                  + " LEFT JOIN nodes src ON e.source_id = src.id "
                  + " LEFT JOIN nodes tgt ON e.target_id = tgt.id "
                  + " WHERE e.source_id = ? AND e.target_id = ? AND e.relation = 'CALLS' "
                  + " LIMIT 1")) {
                ps.setInt(1, i);
                ps.setString(2, src);
                ps.setString(3, tgt);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        EdgeRow r = new EdgeRow();
                        r.source = rs.getString(1);
                        r.target = rs.getString(2);
                        r.externalTargetFqn = rs.getString(3);
                        r.relation = rs.getString(4);
                        r.callKind = rs.getString(5);
                        r.isExternal = rs.getInt(6) == 1;
                        r.sourceFile = rs.getString(7);
                        r.sourceLocation = rs.getString(8);
                        r.depth = rs.getInt(9);
                        r.sourceLabel = rs.getString(10);
                        r.targetLabel = rs.getString(11);
                        r.targetQualifiedName = rs.getString(12);
                        rows.add(r);
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
                "SELECT id, label, kind, qualified_name, source_file, source_location, module"
              + "  FROM nodes WHERE package = ? AND kind IN ("
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
    // FQN resolution
    // ──────────────────────────────────────────────────────────────────

    /** Resolve a free-form input to one or more type node IDs.
     *  Accepts FQN (`com.x.Foo`) or short label (`Foo`). */
    public List<String> resolveTypeIds(String input) {
        if (input == null || input.isEmpty()) return Collections.emptyList();
        // strip a trailing method-part if user passed `Foo#bar` to a type cmd
        String t = input;
        int hash = t.indexOf('#');
        if (hash >= 0) t = t.substring(0, hash);

        // Try exact qualified_name first.
        String sql = "SELECT id FROM nodes WHERE qualified_name = ? AND kind IN ("
                + qmarks(TYPE_KINDS.size()) + ")";
        List<Object> args = new ArrayList<>();
        args.add(t);
        args.addAll(TYPE_KINDS);
        List<String> ids = runStringColumn(sql, args);
        if (!ids.isEmpty()) return ids;

        // Else label match
        sql = "SELECT id FROM nodes WHERE label = ? AND kind IN ("
                + qmarks(TYPE_KINDS.size()) + ") ORDER BY qualified_name";
        args.clear();
        args.add(t);
        args.addAll(TYPE_KINDS);
        return runStringColumn(sql, args);
    }

    /** Resolve a free-form input to one or more method node IDs.
     *  Accepts {@code pkg.Class#method}, {@code pkg.Class#method(p1,p2)},
     *  or {@code Class.method} / {@code method} shorthand. */
    public List<String> resolveMethodIds(String input) {
        if (input == null || input.isEmpty()) return Collections.emptyList();

        // Exact id (with parens)?
        if (input.contains("(") && input.endsWith(")")) {
            List<String> exact = runStringColumn(
                    "SELECT id FROM nodes WHERE kind IN ('METHOD','CONSTRUCTOR') AND id = ?",
                    List.of(input));
            if (!exact.isEmpty()) return exact;
        }

        // `pkg.Class#method` — match qualified_name exactly (any overload).
        if (input.contains("#")) {
            String[] parts = input.split("#", 2);
            String typePart = parts[0];
            String methodPart = parts[1];
            // strip any trailing `(...)` from methodPart for qualified_name match
            int p = methodPart.indexOf('(');
            String mname = p >= 0 ? methodPart.substring(0, p) : methodPart;

            if (typePart.contains(".")) {
                String q = typePart + "#" + mname;
                return runStringColumn(
                        "SELECT id FROM nodes WHERE kind IN ('METHOD','CONSTRUCTOR') "
                      + "  AND qualified_name = ? ORDER BY id", List.of(q));
            } else {
                // short class name
                return runStringColumn(
                        "SELECT id FROM nodes WHERE kind IN ('METHOD','CONSTRUCTOR') "
                      + "  AND qualified_name LIKE ? ORDER BY id",
                        List.of("%." + typePart + "#" + mname));
            }
        }

        // `Class.method` shorthand — split at last dot.
        int dot = input.lastIndexOf('.');
        if (dot > 0) {
            String typePart = input.substring(0, dot);
            String mname = input.substring(dot + 1);
            if (typePart.contains(".")) {
                return runStringColumn(
                        "SELECT id FROM nodes WHERE kind IN ('METHOD','CONSTRUCTOR') "
                      + "  AND qualified_name = ? ORDER BY id",
                        List.of(typePart + "#" + mname));
            } else {
                return runStringColumn(
                        "SELECT id FROM nodes WHERE kind IN ('METHOD','CONSTRUCTOR') "
                      + "  AND qualified_name LIKE ? ORDER BY id",
                        List.of("%." + typePart + "#" + mname));
            }
        }

        // bare method name
        return runStringColumn(
                "SELECT id FROM nodes WHERE kind IN ('METHOD','CONSTRUCTOR') AND label = ? "
              + " ORDER BY qualified_name", List.of(input));
    }

    /** Resolve to a single NodeRow when caller wants one row (e.g. context). */
    public NodeRow resolveNodeRow(String input) {
        // Try as method first if input contains '#' or paren.
        if (input.contains("#") || input.contains("(")) {
            List<String> mids = resolveMethodIds(input);
            if (!mids.isEmpty()) return readNodeById(mids.get(0));
        }
        List<String> tids = resolveTypeIds(input);
        if (!tids.isEmpty()) return readNodeById(tids.get(0));
        // last resort: method shorthand
        List<String> mids = resolveMethodIds(input);
        if (!mids.isEmpty()) return readNodeById(mids.get(0));
        return null;
    }

    // ──────────────────────────────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────────────────────────────

    private NodeRow readNodeById(String id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, label, kind, qualified_name, source_file, source_location, module "
              + " FROM nodes WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readNodeRow(rs) : null;
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }
    }

    private List<NodeRow> runNodeQuery(String sql, List<Object> args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<NodeRow> out = new ArrayList<>();
                while (rs.next()) out.add(readNodeRow(rs));
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
                while (rs.next()) {
                    EdgeRow r = new EdgeRow();
                    r.source = rs.getString(1);
                    r.target = rs.getString(2);
                    r.externalTargetFqn = rs.getString(3);
                    r.relation = rs.getString(4);
                    r.callKind = rs.getString(5);
                    r.isExternal = rs.getInt(6) == 1;
                    r.sourceFile = rs.getString(7);
                    r.sourceLocation = rs.getString(8);
                    r.depth = rs.getInt(9);
                    r.sourceLabel = rs.getString(10);
                    r.targetLabel = rs.getString(11);
                    r.targetQualifiedName = rs.getString(12);
                    out.add(r);
                }
                return out;
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }
    }

    private List<String> runStringColumn(String sql, List<Object> args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }
    }

    private static NodeRow readNodeRow(ResultSet rs) throws SQLException {
        NodeRow n = new NodeRow();
        n.id = rs.getString("id");
        n.label = rs.getString("label");
        n.kind = rs.getString("kind");
        n.qualifiedName = rs.getString("qualified_name");
        n.sourceFile = rs.getString("source_file");
        n.sourceLocation = rs.getString("source_location");
        n.module = rs.getString("module");
        return n;
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
}
