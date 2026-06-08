package com.anatomist.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.anatomist.query.QueryInfra.*;

public class DependencyService {

    private final Connection conn;
    private final NodeResolver resolver;

    public DependencyService(Connection conn, NodeResolver resolver) {
        this.conn = conn;
        this.resolver = resolver;
    }

    public List<EdgeRow> depsOf(String typeRef) {
        List<String> sources = expandTypeToMembers(typeRef);
        if (sources.isEmpty()) return Collections.emptyList();
        String ph = qmarks(sources.size());
        String sql = "SELECT " + RowMappers.edgeColsFlat("1")
                + RowMappers.EDGE_FROM_JOINS
                + " WHERE e.source_id IN (" + ph + ") "
                + "   AND e.relation IN ('CALLS','REFERENCES','WIRES') "
                + " ORDER BY e.relation, e.source_id";
        return runEdgeQuery(conn, sql, new ArrayList<>(sources));
    }

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
        return runEdgeQuery(conn, sql, new ArrayList<>(targets));
    }

    public List<EdgeRow> fieldReaders(String fieldRef) {
        return fieldEdgeQuery(fieldRef, "READS");
    }

    public List<EdgeRow> fieldWriters(String fieldRef) {
        return fieldEdgeQuery(fieldRef, "WRITES");
    }

    private List<EdgeRow> fieldEdgeQuery(String fieldRef, String relation) {
        List<String> fieldIds = resolver.resolveFieldIds(fieldRef);
        if (fieldIds.isEmpty()) return Collections.emptyList();
        String ph = qmarks(fieldIds.size());
        String sql = "SELECT " + RowMappers.edgeColsFlat("1")
                + RowMappers.EDGE_FROM_JOINS
                + " WHERE e.target_id IN (" + ph + ") AND e.relation = ? AND e.is_external = 0 "
                + " ORDER BY e.source_id, e.source_location";
        List<Object> args = new ArrayList<>(fieldIds);
        args.add(relation);
        return runEdgeQuery(conn, sql, args);
    }

    private List<String> expandTypeToMembers(String typeRef) {
        List<String> typeIds = resolver.resolveTypeIds(typeRef);
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
}
