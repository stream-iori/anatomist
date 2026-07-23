package com.anatomist.query;

import com.anatomist.model.GraphConstants;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.anatomist.query.QueryInfra.*;

public class SearchService {

    private final Connection conn;
    private final NodeResolver resolver;

    public SearchService(Connection conn, NodeResolver resolver) {
        this.conn = conn;
        this.resolver = resolver;
    }

    public List<NodeRow> search(String term, String kind, int limit) {
        return search(term, kind, limit, 0);
    }

    public List<NodeRow> search(String term, String kind, int limit, int offset) {
        String ftsExpr = term == null ? "" : term.trim();
        if (ftsExpr.isEmpty()) return Collections.emptyList();
        if (!containsFtsSyntax(ftsExpr)) ftsExpr = ftsExpr + "*";

        List<NodeRow> rows = new ArrayList<>(searchReal(ftsExpr, kind));
        if (allowsExternalTypes(kind) && !containsFtsSyntax(term == null ? "" : term.trim())) {
            rows.addAll(externalTypes("%" + escapeLike(term.trim().toLowerCase(Locale.ROOT)) + "%", false));
        }
        return page(rows, limit > 0 ? limit : 20, offset);
    }

    private List<NodeRow> searchReal(String ftsExpr, String kind) {
        if (GraphConstants.Kind.EXTERNAL_CLASS.equals(kind)) return Collections.emptyList();
        StringBuilder sql = new StringBuilder()
                .append("SELECT ").append(RowMappers.NODE_COLS).append(" ")
                .append("FROM node_names nn ")
                .append("JOIN nodes n ON nn.rowid = n.rowid ")
                .append("WHERE node_names MATCH ? ");
        List<Object> args = new ArrayList<>();
        args.add(ftsExpr);
        sql.append(resolver.selectorClause("n")).append(' ');
        if (kind != null && !kind.isEmpty()) {
            sql.append("AND n.kind = ? ");
            args.add(kind);
        }
        sql.append("ORDER BY rank");
        return runNodeQuery(conn, sql.toString(), args);
    }

    public List<NodeRow> searchByAnnotation(String annotationTerm, String kind, int limit) {
        return searchByAnnotation(annotationTerm, kind, limit, 0);
    }

    public List<NodeRow> searchByAnnotation(String annotationTerm, String kind, int limit, int offset) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT DISTINCT ").append(RowMappers.NODE_COLS).append(" ")
                .append("FROM nodes n JOIN annotations a ON n.id = a.node_id ")
                .append("WHERE a.annotation_fqn LIKE ? ");
        String like = "%" + annotationTerm.replace("@", "") + "%";
        List<Object> args = new ArrayList<>();
        args.add(like);
        sql.append(resolver.selectorClause("n")).append(' ');
        if (kind != null && !kind.isEmpty()) {
            sql.append("AND n.kind = ? ");
            args.add(kind);
        }
        sql.append("ORDER BY n.qualified_name LIMIT ? OFFSET ?");
        args.add(limit > 0 ? limit : 50);
        args.add(Math.max(0, offset));
        return runNodeQuery(conn, sql.toString(), args);
    }

    /** Precise simple-name match against {@code nodes.label} (glob: {@code *}→%, {@code ?}→_),
     *  bypassing FTS. Distinct from {@link #search} which matches the FTS index (incl. package path). */
    public List<NodeRow> searchByName(String glob, String kind, int limit) {
        return searchByName(glob, kind, limit, 0);
    }

    public List<NodeRow> searchByName(String glob, String kind, int limit, int offset) {
        List<NodeRow> rows = new ArrayList<>(searchByNameReal(glob, kind));
        if (allowsExternalTypes(kind)) rows.addAll(externalTypes(globToLike(glob).toLowerCase(Locale.ROOT), true));
        return page(rows, limit > 0 ? limit : 50, offset);
    }

    private List<NodeRow> searchByNameReal(String glob, String kind) {
        if (GraphConstants.Kind.EXTERNAL_CLASS.equals(kind)) return Collections.emptyList();
        StringBuilder sql = new StringBuilder("SELECT ").append(RowMappers.NODE_COLS)
                .append(" FROM nodes n WHERE n.label LIKE ? ");
        List<Object> args = new ArrayList<>();
        args.add(globToLike(glob));
        sql.append(resolver.selectorClause("n")).append(' ');
        if (kind != null && !kind.isEmpty()) { sql.append("AND n.kind = ? "); args.add(kind); }
        sql.append("ORDER BY n.qualified_name");
        return runNodeQuery(conn, sql.toString(), args);
    }

    /** True count of {@link #searchByName} matches, independent of any LIMIT. */
    public int countByName(String glob, String kind) {
        return searchByNameReal(glob, kind).size()
                + (allowsExternalTypes(kind)
                ? externalTypes(globToLike(glob).toLowerCase(Locale.ROOT), true).size() : 0);
    }

    /** True count of FTS {@link #search} matches, independent of any LIMIT. */
    public int countSearch(String term, String kind) {
        String ftsExpr = term == null ? "" : term.trim();
        if (ftsExpr.isEmpty()) return 0;
        if (!containsFtsSyntax(ftsExpr)) ftsExpr = ftsExpr + "*";
        int count = searchReal(ftsExpr, kind).size();
        if (allowsExternalTypes(kind) && !containsFtsSyntax(term.trim())) {
            count += externalTypes("%" + escapeLike(term.trim().toLowerCase(Locale.ROOT)) + "%", false).size();
        }
        return count;
    }

    public int countByAnnotation(String annotationTerm, String kind) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT COUNT(DISTINCT n.id) ")
                .append("FROM nodes n JOIN annotations a ON n.id = a.node_id ")
                .append("WHERE a.annotation_fqn LIKE ? ");
        List<Object> args = new ArrayList<>();
        args.add("%" + annotationTerm.replace("@", "") + "%");
        sql.append(resolver.selectorClause("n")).append(' ');
        if (kind != null && !kind.isEmpty()) { sql.append("AND n.kind = ? "); args.add(kind); }
        return runScalarInt(conn, sql.toString(), args);
    }

    private static String globToLike(String glob) {
        return glob == null ? "%" : glob.replace('*', '%').replace('?', '_');
    }

    private boolean allowsExternalTypes(String kind) {
        return kind == null || kind.isBlank() || GraphConstants.Kind.EXTERNAL_CLASS.equals(kind);
    }

    /** Query-only EXTERNAL_CLASS rows. The classpath declaration itself is deliberately not indexed. */
    private List<NodeRow> externalTypes(String like, boolean simpleName) {
        String type = "CASE WHEN instr(e.external_target_fqn, '#') > 0 "
                + "THEN substr(e.external_target_fqn, 1, instr(e.external_target_fqn, '#') - 1) "
                + "ELSE e.external_target_fqn END";
        String match = simpleName
                ? "(LOWER(" + type + ") LIKE ? ESCAPE '\\' OR LOWER(" + type + ") = ?)"
                : "LOWER(" + type + ") LIKE ? ESCAPE '\\'";
        String sql = "SELECT " + type + " AS type_fqn,e.relation,"
                + "COALESCE(e.resolution, ?) AS resolution,e.confidence,COUNT(*) AS edge_count "
                + "FROM edges e JOIN nodes src ON e.source_id=src.id "
                + "WHERE e.is_external=1 AND " + match + " "
                + resolver.selectorClause("src") + " "
                + "GROUP BY type_fqn,e.relation,COALESCE(e.resolution, ?),e.confidence "
                + "ORDER BY type_fqn";
        Map<String, NodeRow> rows = new LinkedHashMap<>();
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setString(1, GraphConstants.Resolution.CLASSPATH);
            statement.setString(2, simpleName ? "%." + like : like);
            int groupArg = 3;
            if (simpleName) {
                statement.setString(3, like);
                groupArg = 4;
            }
            statement.setString(groupArg, GraphConstants.Resolution.CLASSPATH);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String fqn = result.getString("type_fqn");
                    NodeRow row = rows.computeIfAbsent(fqn, this::externalTypeRow);
                    long count = result.getLong("edge_count");
                    row.externalEdgeCount += count;
                    increment(row.relationCounts, result.getString("relation"), count);
                    increment(row.resolutionCounts, result.getString("resolution"), count);
                    increment(row.confidenceCounts, result.getString("confidence"), count);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to search external targets", e);
        }
        List<NodeRow> result = new ArrayList<>(rows.values());
        result.sort(Comparator.comparing((NodeRow row) -> row.externalEdgeCount).reversed()
                .thenComparing(row -> row.qualifiedName));
        return result;
    }

    private NodeRow externalTypeRow(String fqn) {
        NodeRow row = new NodeRow();
        row.id = fqn;
        row.symbolId = fqn;
        row.qualifiedName = fqn;
        int dot = fqn.lastIndexOf('.');
        row.label = dot < 0 ? fqn : fqn.substring(dot + 1);
        row.kind = GraphConstants.Kind.EXTERNAL_CLASS;
        row.externalTarget = Boolean.TRUE;
        row.externalEdgeCount = 0L;
        row.relationCounts = new LinkedHashMap<>();
        row.resolutionCounts = new LinkedHashMap<>();
        row.confidenceCounts = new LinkedHashMap<>();
        return row;
    }

    private static void increment(Map<String, Long> counts, String key, long count) {
        counts.merge(key == null ? "unknown" : key, count, Long::sum);
    }

    private static List<NodeRow> page(List<NodeRow> rows, int limit, int offset) {
        int start = Math.min(Math.max(offset, 0), rows.size());
        int end = Math.min(start + limit, rows.size());
        return rows.subList(start, end);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean containsFtsSyntax(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (isAsciiRegexWhitespace(character)
                    || character == '"' || character == '(' || character == ')'
                    || character == ':' || character == '*' || character == '-') {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiRegexWhitespace(char character) {
        return character == ' ' || character == '\t' || character == '\n'
                || character == '\u000B' || character == '\f' || character == '\r';
    }

    public List<NodeRow> implementorsOf(String typeRef) {
        return implementorsOf(typeRef, false);
    }

    public List<NodeRow> implementorsOf(String typeRef, boolean recursive) {
        List<String> targetIds = resolver.resolveTypeIds(typeRef);
        if (targetIds.isEmpty()) return Collections.emptyList();
        String placeholders = qmarks(targetIds.size());
        String sql;
        if (recursive) {
            sql = "WITH RECURSIVE impl(id) AS ("
                + "  SELECT source_id FROM edges"
                + "   WHERE relation IN (" + sqlIn(GraphConstants.HIERARCHY_RELATIONS) + ") AND is_external = 0"
                + "     AND target_id IN (" + placeholders + ")"
                + "  UNION"
                + "  SELECT e.source_id FROM edges e JOIN impl ON e.target_id = impl.id"
                + "   WHERE e.relation IN (" + sqlIn(GraphConstants.HIERARCHY_RELATIONS) + ") AND e.is_external = 0"
                + ") SELECT " + RowMappers.NODE_COLS
                + " FROM nodes n JOIN impl ON n.id = impl.id ORDER BY n.qualified_name";
        } else {
            sql = "SELECT " + RowMappers.NODE_COLS
                + " FROM edges e JOIN nodes n ON e.source_id = n.id "
                + " WHERE e.relation IN (" + sqlIn(GraphConstants.HIERARCHY_RELATIONS) + ") "
                + "   AND e.is_external = 0 AND e.target_id IN (" + placeholders + ") "
                + " ORDER BY n.qualified_name";
        }
        return runNodeQuery(conn, sql, new ArrayList<>(targetIds));
    }

    /** True count of implementors, independent of any LIMIT. */
    public int countImplementorsOf(String typeRef, boolean recursive) {
        List<String> targetIds = resolver.resolveTypeIds(typeRef);
        if (targetIds.isEmpty()) return 0;
        String placeholders = qmarks(targetIds.size());
        String sql;
        if (recursive) {
            sql = "WITH RECURSIVE impl(id) AS ("
                + "  SELECT source_id FROM edges"
                + "   WHERE relation IN (" + sqlIn(GraphConstants.HIERARCHY_RELATIONS) + ") AND is_external = 0"
                + "     AND target_id IN (" + placeholders + ")"
                + "  UNION"
                + "  SELECT e.source_id FROM edges e JOIN impl ON e.target_id = impl.id"
                + "   WHERE e.relation IN (" + sqlIn(GraphConstants.HIERARCHY_RELATIONS) + ") AND e.is_external = 0"
                + ") SELECT COUNT(*) FROM impl";
        } else {
            sql = "SELECT COUNT(DISTINCT e.source_id) FROM edges e"
                + " WHERE e.relation IN (" + sqlIn(GraphConstants.HIERARCHY_RELATIONS) + ")"
                + "   AND e.is_external = 0 AND e.target_id IN (" + placeholders + ")";
        }
        return runScalarInt(conn, sql, new ArrayList<>(targetIds));
    }
}
