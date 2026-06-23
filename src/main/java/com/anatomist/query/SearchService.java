package com.anatomist.query;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
        sql.append("ORDER BY rank LIMIT ? OFFSET ?");
        args.add(limit > 0 ? limit : 20);
        args.add(Math.max(0, offset));
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
        StringBuilder sql = new StringBuilder("SELECT ").append(RowMappers.NODE_COLS)
                .append(" FROM nodes n WHERE n.label LIKE ? ");
        List<Object> args = new ArrayList<>();
        args.add(globToLike(glob));
        if (kind != null && !kind.isEmpty()) { sql.append("AND n.kind = ? "); args.add(kind); }
        sql.append("ORDER BY n.qualified_name LIMIT ? OFFSET ?");
        args.add(limit > 0 ? limit : 50);
        args.add(Math.max(0, offset));
        return runNodeQuery(conn, sql.toString(), args);
    }

    /** True count of {@link #searchByName} matches, independent of any LIMIT. */
    public int countByName(String glob, String kind) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM nodes n WHERE n.label LIKE ? ");
        List<Object> args = new ArrayList<>();
        args.add(globToLike(glob));
        if (kind != null && !kind.isEmpty()) { sql.append("AND n.kind = ? "); args.add(kind); }
        return runScalarInt(conn, sql.toString(), args);
    }

    /** True count of FTS {@link #search} matches, independent of any LIMIT. */
    public int countSearch(String term, String kind) {
        String ftsExpr = term == null ? "" : term.trim();
        if (ftsExpr.isEmpty()) return 0;
        if (!ftsExpr.matches(".*[\\s\"():*-].*")) ftsExpr = ftsExpr + "*";
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM node_names nn JOIN nodes n ON nn.rowid = n.rowid "
              + "WHERE node_names MATCH ? ");
        List<Object> args = new ArrayList<>();
        args.add(ftsExpr);
        if (kind != null && !kind.isEmpty()) { sql.append("AND n.kind = ? "); args.add(kind); }
        return runScalarInt(conn, sql.toString(), args);
    }

    public int countByAnnotation(String annotationTerm, String kind) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT COUNT(DISTINCT n.id) ")
                .append("FROM nodes n JOIN annotations a ON n.id = a.node_id ")
                .append("WHERE a.annotation_fqn LIKE ? ");
        List<Object> args = new ArrayList<>();
        args.add("%" + annotationTerm.replace("@", "") + "%");
        if (kind != null && !kind.isEmpty()) { sql.append("AND n.kind = ? "); args.add(kind); }
        return runScalarInt(conn, sql.toString(), args);
    }

    private static String globToLike(String glob) {
        return glob == null ? "%" : glob.replace('*', '%').replace('?', '_');
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
                + "   WHERE relation IN ('IMPLEMENTS','INHERITS') AND is_external = 0"
                + "     AND target_id IN (" + placeholders + ")"
                + "  UNION"
                + "  SELECT e.source_id FROM edges e JOIN impl ON e.target_id = impl.id"
                + "   WHERE e.relation IN ('IMPLEMENTS','INHERITS') AND e.is_external = 0"
                + ") SELECT " + RowMappers.NODE_COLS
                + " FROM nodes n JOIN impl ON n.id = impl.id ORDER BY n.qualified_name";
        } else {
            sql = "SELECT " + RowMappers.NODE_COLS
                + " FROM edges e JOIN nodes n ON e.source_id = n.id "
                + " WHERE e.relation IN ('IMPLEMENTS','INHERITS') "
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
                + "   WHERE relation IN ('IMPLEMENTS','INHERITS') AND is_external = 0"
                + "     AND target_id IN (" + placeholders + ")"
                + "  UNION"
                + "  SELECT e.source_id FROM edges e JOIN impl ON e.target_id = impl.id"
                + "   WHERE e.relation IN ('IMPLEMENTS','INHERITS') AND e.is_external = 0"
                + ") SELECT COUNT(*) FROM impl";
        } else {
            sql = "SELECT COUNT(DISTINCT e.source_id) FROM edges e"
                + " WHERE e.relation IN ('IMPLEMENTS','INHERITS')"
                + "   AND e.is_external = 0 AND e.target_id IN (" + placeholders + ")";
        }
        return runScalarInt(conn, sql, new ArrayList<>(targetIds));
    }
}
