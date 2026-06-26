package com.anatomist.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class QueryInfra {

    static final int BATCH_SIZE = 500;

    private QueryInfra() {}

    static List<NodeRow> runNodeQuery(Connection conn, String sql, List<Object> args) {
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

    static List<EdgeRow> runEdgeQuery(Connection conn, String sql, List<Object> args) {
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

    /** Runs a single-column {@code COUNT(*)}-style query and returns the scalar int. */
    static int runScalarInt(Connection conn, String sql, List<Object> args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }
    }

    static void bind(PreparedStatement ps, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            Object v = args.get(i);
            if (v instanceof Integer iv) ps.setInt(i + 1, iv);
            else ps.setString(i + 1, v == null ? null : v.toString());
        }
    }

    static void bindStrings(PreparedStatement ps, List<String> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            ps.setString(i + 1, values.get(i));
        }
    }

    static String qmarks(int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ",?");
        return sb.toString();
    }

    static String shortName(String fqn) {
        if (fqn == null) return null;
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }

    static RuntimeException rethrow(SQLException e) {
        return new RuntimeException("query failed: " + e.getMessage(), e);
    }

    static boolean readBool(ResultSet rs, String column) throws SQLException {
        return rs.getInt(column) == 1;
    }

    @FunctionalInterface
    interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    static <T> List<T> queryList(Connection conn, String sql, RowMapper<T> mapper) {
        List<T> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(mapper.map(rs));
        } catch (SQLException e) {
            throw rethrow(e);
        }
        return out;
    }
}
