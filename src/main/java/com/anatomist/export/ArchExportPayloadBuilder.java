package com.anatomist.export;

import com.anatomist.json.Json;
import com.anatomist.model.GraphConstants;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ArchExportPayloadBuilder {

    private final Connection conn;

    public ArchExportPayloadBuilder(Connection conn) {
        this.conn = conn;
    }

    public Map<String, Object> build(Path sourceRoot, int maxEdges, int maxSnippets) {
        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put("project_name", projectName(sourceRoot));
        payload.put("stats", buildStats());
        payload.put("types", buildTypes());
        payload.put("members", buildMembers());
        payload.put("type_edges", buildTypeEdges(maxEdges));

        if (sourceRoot != null && maxSnippets > 0) {
            payload.put("code_snippets", buildCodeSnippets(sourceRoot, maxSnippets, payload));
        } else {
            payload.put("code_snippets", new LinkedHashMap<>());
        }

        return payload;
    }

    private static String projectName(Path sourceRoot) {
        if (sourceRoot == null) return "unknown";
        Path fileName = sourceRoot.getFileName();
        return fileName != null ? fileName.toString() : sourceRoot.toString();
    }

    // ───────────────────── stats ─────────────────────────────────

    private Map<String, Object> buildStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        Map<String, Long> kindCounts = new LinkedHashMap<>();
        query("SELECT kind, COUNT(*) FROM nodes GROUP BY kind ORDER BY kind", rs -> {
            kindCounts.put(rs.getString(1), rs.getLong(2));
        });
        stats.put("kind_counts", kindCounts);

        Map<String, Long> internalEdge = new LinkedHashMap<>();
        Map<String, Long> externalEdge = new LinkedHashMap<>();
        query("SELECT relation, is_external, COUNT(*) FROM edges "
                + "GROUP BY relation, is_external ORDER BY relation", rs -> {
            String rel = rs.getString(1);
            long count = rs.getLong(3);
            if (rs.getInt(2) == 1) externalEdge.merge(rel, count, Long::sum);
            else internalEdge.merge(rel, count, Long::sum);
        });
        stats.put("internal_edge_counts", internalEdge);
        stats.put("external_edge_counts", externalEdge);

        long[] pkgCount = {0};
        query("SELECT COUNT(DISTINCT package) FROM nodes WHERE package IS NOT NULL", rs -> {
            pkgCount[0] = rs.getLong(1);
        });
        stats.put("package_count", pkgCount[0]);

        return stats;
    }

    // ───────────────────── types ─────────────────────────────────

    private List<Map<String, Object>> buildTypes() {
        Map<String, List<String>> annotationsByNode = loadTypeAnnotations();

        String sql = "SELECT n.id, n.label, n.kind, n.qualified_name, n.package, n.javadoc, n.metadata"
                + " FROM nodes n"
                + " WHERE n.kind IN ('CLASS','INTERFACE','ENUM','ANNOTATION','RECORD')"
                + " ORDER BY n.qualified_name";

        List<Map<String, Object>> types = new ArrayList<>();
        query(sql, rs -> {
            Map<String, Object> t = new LinkedHashMap<>();
            String id = rs.getString("id");
            t.put("id", id);
            t.put("label", rs.getString("label"));
            t.put("kind", rs.getString("kind"));
            t.put("qualified_name", rs.getString("qualified_name"));
            t.put("package", rs.getString("package"));
            t.put("is_abstract", extractIsAbstract(rs.getString("metadata")));
            t.put("annotations", annotationsByNode.getOrDefault(id, List.of()));

            int[] memberCounts = countMembers(id);
            t.put("method_count", memberCounts[0]);
            t.put("field_count", memberCounts[1]);

            String javadoc = rs.getString("javadoc");
            t.put("javadoc", javadoc);
            types.add(t);
        });
        return types;
    }

    private int[] countMembers(String typeId) {
        int[] counts = {0, 0}; // [methods, fields]
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT n.kind, COUNT(*) FROM edges e JOIN nodes n ON e.target_id = n.id"
                + " WHERE e.source_id = ? AND e.relation = 'CONTAINS' AND e.is_external = 0"
                + "   AND n.kind IN ('METHOD','CONSTRUCTOR','FIELD')"
                + " GROUP BY n.kind")) {
            ps.setString(1, typeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String kind = rs.getString(1);
                    int count = rs.getInt(2);
                    if ("FIELD".equals(kind)) counts[1] = count;
                    else counts[0] += count;
                }
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }
        return counts;
    }

    private Map<String, List<String>> loadTypeAnnotations() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        String sql = "SELECT node_id, annotation_fqn FROM annotations"
                + " WHERE node_id IN (SELECT id FROM nodes WHERE kind IN ('CLASS','INTERFACE','ENUM','ANNOTATION','RECORD'))"
                + " ORDER BY node_id";
        try {
            query(sql, rs -> {
                String nodeId = rs.getString(1);
                String fqn = rs.getString(2);
                result.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(shortName(fqn));
            });
        } catch (RuntimeException ignored) {
            // annotations table may not exist in older indexes
        }
        return result;
    }

    // ───────────────────── members ───────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, List<Map<String, Object>>> buildMembers() {
        Map<String, List<Map<String, Object>>> members = new LinkedHashMap<>();

        String sql = "SELECT e.source_id AS type_id, n.id, n.label, n.kind, n.metadata,"
                + "       n.source_location, n.javadoc, n.source_file"
                + " FROM edges e"
                + " JOIN nodes n ON e.target_id = n.id"
                + " WHERE e.relation = 'CONTAINS' AND e.is_external = 0"
                + "   AND n.kind IN ('METHOD','CONSTRUCTOR','FIELD')"
                + " ORDER BY e.source_id, n.kind, n.label";

        query(sql, rs -> {
            String typeId = rs.getString("type_id");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("label", rs.getString("label"));
            m.put("kind", rs.getString("kind"));

            String metadata = rs.getString("metadata");
            Map<String, Object> meta = parseMetadataSafe(metadata);

            m.put("signature", meta.get("signature"));
            m.put("return_type", meta.get("returnType"));

            Object modifiers = meta.get("modifiers");
            m.put("modifiers", modifiers instanceof List ? modifiers : List.of());

            Object annotations = meta.get("annotations");
            if (annotations instanceof List<?> annList) {
                List<String> shortNames = new ArrayList<>();
                for (Object a : annList) {
                    shortNames.add(a instanceof String s ? shortName(s) : String.valueOf(a));
                }
                m.put("annotations", shortNames);
            } else {
                m.put("annotations", List.of());
            }

            String loc = rs.getString("source_location");
            m.put("source_location", loc);
            m.put("javadoc", rs.getString("javadoc"));

            members.computeIfAbsent(typeId, k -> new ArrayList<>()).add(m);
        });

        return members;
    }

    // ───────────────────── type edges ────────────────────────────

    private List<Map<String, Object>> buildTypeEdges(int maxEdges) {
        String sql =
                "WITH RECURSIVE owner(node_id, cur_id, cur_kind) AS ("
              + "  SELECT id, id, kind FROM nodes"
              + "  UNION ALL"
              + "  SELECT o.node_id, c.source_id, p.kind"
              + "  FROM owner o"
              + "  JOIN edges c INDEXED BY idx_edges_target_relation"
              + "       ON c.target_id = o.cur_id AND c.relation = 'CONTAINS' AND c.is_external = 0"
              + "  JOIN nodes p ON p.id = c.source_id"
              + "  WHERE o.cur_kind NOT IN ('CLASS','INTERFACE','ENUM','ANNOTATION','RECORD')"
              + "), type_of AS ("
              + "  SELECT node_id, cur_id AS type_id FROM owner"
              + "  WHERE cur_kind IN ('CLASS','INTERFACE','ENUM','ANNOTATION','RECORD')"
              + ") "
              + "SELECT st.id AS source, tt.id AS target,"
              + "       CASE WHEN e.relation IN ('IMPLEMENTS','INHERITS') THEN e.relation ELSE 'CALLS' END AS relation,"
              + "       COUNT(*) AS edge_count"
              + " FROM edges e"
              + " JOIN type_of so ON e.source_id = so.node_id"
              + " JOIN type_of ot ON e.target_id = ot.node_id"
              + " JOIN nodes st ON so.type_id = st.id"
              + " JOIN nodes tt ON ot.type_id = tt.id"
              + " WHERE e.is_external = 0"
              + "   AND e.relation IN ('CALLS','REFERENCES','WIRES','IMPLEMENTS','INHERITS')"
              + "   AND so.type_id <> ot.type_id"
              + " GROUP BY st.id, tt.id, CASE WHEN e.relation IN ('IMPLEMENTS','INHERITS') THEN e.relation ELSE 'CALLS' END"
              + " ORDER BY edge_count DESC"
              + (maxEdges > 0 ? " LIMIT " + maxEdges : "");

        List<Map<String, Object>> edges = new ArrayList<>();
        query(sql, rs -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("source", rs.getString("source"));
            e.put("target", rs.getString("target"));
            e.put("relation", rs.getString("relation"));
            e.put("edge_count", rs.getInt("edge_count"));
            edges.add(e);
        });
        return edges;
    }

    // ───────────────────── code snippets ─────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, String> buildCodeSnippets(Path sourceRoot, int maxSnippets,
                                                   Map<String, Object> payload) {
        Map<String, String> snippets = new LinkedHashMap<>();
        Map<String, List<String>> fileCache = new LinkedHashMap<>();

        // Collect all methods/constructors with source locations, prioritized by
        // type edge count descending.
        List<SnippetCandidate> candidates = new ArrayList<>();

        Map<String, List<Map<String, Object>>> members =
                (Map<String, List<Map<String, Object>>>) payload.get("members");
        List<Map<String, Object>> types = (List<Map<String, Object>>) payload.get("types");
        List<Map<String, Object>> typeEdges = (List<Map<String, Object>>) payload.get("type_edges");

        // Build edge count per type for prioritization
        Map<String, Integer> edgeCountByType = new LinkedHashMap<>();
        if (typeEdges != null) {
            for (Map<String, Object> edge : typeEdges) {
                String src = (String) edge.get("source");
                int count = ((Number) edge.get("edge_count")).intValue();
                edgeCountByType.merge(src, count, Integer::sum);
            }
        }

        // Collect snippet candidates from all types
        if (types != null && members != null) {
            for (Map<String, Object> type : types) {
                String typeId = (String) type.get("id");
                int typeEdgeCount = edgeCountByType.getOrDefault(typeId, 0);
                List<Map<String, Object>> typeMembers = members.get(typeId);
                if (typeMembers == null) continue;

                for (Map<String, Object> member : typeMembers) {
                    String kind = (String) member.get("kind");
                    if (!GraphConstants.METHOD_KINDS.contains(kind)) continue;

                    String loc = (String) member.get("source_location");
                    if (loc == null || !loc.startsWith("L")) continue;

                    String memberId = (String) member.get("id");
                    candidates.add(new SnippetCandidate(memberId, typeId, typeEdgeCount, loc));
                }
            }
        }

        // Sort by edge count descending.
        candidates.sort((a, b) -> Integer.compare(b.typeEdgeCount, a.typeEdgeCount));

        // Resolve source files for member IDs
        Map<String, String> sourceFileByMember = new LinkedHashMap<>();
        String sql = "SELECT n.id, n.source_file FROM nodes n"
                + " WHERE n.kind IN ('METHOD','CONSTRUCTOR') AND n.source_file IS NOT NULL";
        query(sql, rs -> {
            sourceFileByMember.put(rs.getString(1), rs.getString(2));
        });

        int collected = 0;
        for (SnippetCandidate c : candidates) {
            if (collected >= maxSnippets) break;

            String sourceFile = sourceFileByMember.get(c.memberId);
            if (sourceFile == null) continue;

            Path resolved = sourceRoot.resolve(sourceFile);
            List<String> lines = fileCache.computeIfAbsent(sourceFile, k -> {
                try {
                    if (!Files.exists(resolved)) return List.of();
                    return Files.readAllLines(resolved);
                } catch (IOException e) {
                    return List.of();
                }
            });
            if (lines.isEmpty()) continue;

            int startLine = parseLineNumber(c.sourceLocation);
            if (startLine <= 0) continue;

            String body = extractMethodBody(lines, startLine);
            if (body == null) continue;

            snippets.put(c.memberId, body);
            collected++;
        }

        return snippets;
    }

    private String extractMethodBody(List<String> lines, int startLine) {
        int idx = startLine - 1;
        if (idx < 0 || idx >= lines.size()) return null;

        // Scan backwards from startLine-1 to include annotations above the method
        int annotStart = idx;
        for (int i = idx - 1; i >= 0; i--) {
            String trimmed = lines.get(i).stripLeading();
            if (trimmed.startsWith("@")) {
                annotStart = i;
            } else {
                break;
            }
        }

        int braceCount = 0;
        boolean foundOpen = false;
        StringBuilder sb = new StringBuilder();
        for (int i = annotStart; i < lines.size(); i++) {
            String line = lines.get(i);
            sb.append(line).append('\n');
            for (char c : line.toCharArray()) {
                if (c == '{') { braceCount++; foundOpen = true; }
                else if (c == '}') braceCount--;
            }
            if (foundOpen && braceCount <= 0) break;
        }
        return sb.toString().stripTrailing();
    }

    private static int parseLineNumber(String loc) {
        if (loc == null || !loc.startsWith("L")) return -1;
        try {
            return Integer.parseInt(loc.substring(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ───────────────────── helpers ───────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseMetadataSafe(String metadata) {
        if (metadata == null || metadata.isBlank()) return Map.of();
        try {
            Object parsed = Json.parseTree(metadata);
            if (parsed instanceof Map<?, ?> m) return (Map<String, Object>) m;
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static boolean extractIsAbstract(String metadata) {
        Map<String, Object> meta = parseMetadataSafe(metadata);
        Object val = meta.get("isAbstract");
        if (val instanceof Boolean b) return b;
        if (val instanceof Number n) return n.intValue() != 0;
        return false;
    }

    private static String shortName(String fqn) {
        if (fqn == null) return null;
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }

    @FunctionalInterface
    private interface RowConsumer {
        void accept(ResultSet rs) throws SQLException;
    }

    private void query(String sql, RowConsumer consumer) {
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) consumer.accept(rs);
        } catch (SQLException e) {
            throw rethrow(e);
        }
    }

    private static RuntimeException rethrow(SQLException e) {
        return new RuntimeException("query failed: " + e.getMessage(), e);
    }

    private record SnippetCandidate(String memberId, String typeId,
                                    int typeEdgeCount, String sourceLocation) {}
}
