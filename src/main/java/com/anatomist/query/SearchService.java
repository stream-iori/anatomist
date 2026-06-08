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
        sql.append("ORDER BY rank LIMIT ?");
        args.add(limit > 0 ? limit : 20);
        return runNodeQuery(conn, sql.toString(), args);
    }

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
        return runNodeQuery(conn, sql.toString(), args);
    }

    public List<NodeRow> implementorsOf(String typeRef) {
        List<String> targetIds = resolver.resolveTypeIds(typeRef);
        if (targetIds.isEmpty()) return Collections.emptyList();

        String placeholders = qmarks(targetIds.size());
        String sql = "SELECT " + RowMappers.NODE_COLS
                + " FROM edges e JOIN nodes n ON e.source_id = n.id "
                + " WHERE e.relation IN ('IMPLEMENTS','INHERITS') "
                + "   AND e.is_external = 0 AND e.target_id IN (" + placeholders + ") "
                + " ORDER BY n.qualified_name";
        return runNodeQuery(conn, sql, new ArrayList<>(targetIds));
    }
}
