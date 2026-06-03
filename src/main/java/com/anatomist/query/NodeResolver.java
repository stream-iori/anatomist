package com.anatomist.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Resolves free-form user input (FQNs, {@code Class#method}, {@code Class.field},
 * bare labels) to concrete node ids. Pure read-over-SQLite string parsing,
 * extracted out of {@link QueryService} so query assembly and identity
 * resolution are separately readable and testable.
 *
 * <p>Shares the caller's {@link Connection}; does not own its lifecycle.</p>
 */
final class NodeResolver {

    private static final Set<String> TYPE_KINDS = Set.of(
            "CLASS", "INTERFACE", "ENUM", "ANNOTATION", "RECORD", "ANONYMOUS_CLASS");

    private final Connection conn;

    NodeResolver(Connection conn) {
        this.conn = conn;
    }

    /** Resolve a free-form input to one or more type node IDs.
     *  Accepts FQN (`com.x.Foo`) or short label (`Foo`). */
    List<String> resolveTypeIds(String input) {
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
    List<String> resolveMethodIds(String input) {
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

    /** Resolve a field reference. Accepts {@code pkg.Class#name},
     *  {@code Class.name}, or a bare {@code name} (matched by label). */
    List<String> resolveFieldIds(String input) {
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

    /** Resolve to a single NodeRow when caller wants one row (e.g. context). */
    NodeRow resolveNodeRow(String input) {
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

    NodeRow readNodeById(String id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + RowMappers.NODE_COLS + " FROM nodes n WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? RowMappers.mapNode(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("query failed: " + e.getMessage(), e);
        }
    }

    private List<String> runStringColumn(String sql, List<Object> args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) {
                Object v = args.get(i);
                ps.setString(i + 1, v == null ? null : v.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException("query failed: " + e.getMessage(), e);
        }
    }

    private static String qmarks(int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ",?");
        return sb.toString();
    }
}
