package com.anatomist.query;

import com.anatomist.model.GraphConstants;
import com.anatomist.core.NodeKeyFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.anatomist.query.QueryInfra.bind;
import static com.anatomist.query.QueryInfra.bindStrings;
import static com.anatomist.query.QueryInfra.qmarks;
import static com.anatomist.query.QueryInfra.sqlIn;

/**
 * Resolves free-form user input (FQNs, {@code Class#method}, {@code Class.field},
 * bare labels) to concrete node ids. Pure read-over-SQLite string parsing,
 * extracted out of {@link QueryService} so query assembly and identity
 * resolution are separately readable and testable.
 *
 * <p>Shares the caller's {@link Connection}; does not own its lifecycle.</p>
 */
final class NodeResolver {

    private static final Set<String> EXACT_CALLABLE_KINDS = Set.of(
            GraphConstants.Kind.METHOD,
            GraphConstants.Kind.CONSTRUCTOR,
            GraphConstants.Kind.LAMBDA,
            GraphConstants.Kind.METHOD_REF);

    private final Connection conn;
    private final Map<String, NodeRow> nodeCache = new HashMap<>();
    private String module;
    private String scope = "MAIN";

    NodeResolver(Connection conn) {
        this.conn = conn;
    }

    void select(String module, String scope) {
        this.module = module == null || module.isBlank() ? null : module;
        this.scope = scope == null || scope.isBlank() ? "MAIN" : scope.toUpperCase();
        if (!Set.of("MAIN", "TEST", "GENERATED", "ALL").contains(this.scope)) {
            throw new IllegalArgumentException("scope must be MAIN, TEST, GENERATED, or ALL: " + scope);
        }
    }

    private String selectorClause() {
        return selectorClause(null);
    }

    String selectorClause(String alias) {
        String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
        StringBuilder sql = new StringBuilder();
        if (!"ALL".equals(scope)) sql.append(" AND ").append(prefix).append("scope='")
                .append(quote(scope)).append("'");
        if (module != null) sql.append(" AND ").append(prefix).append("module='")
                .append(quote(module)).append("'");
        return sql.toString();
    }

    private static String quote(String value) { return value.replace("'", "''"); }

    /** Resolve a free-form input to one or more type node IDs.
     *  Accepts FQN (`com.x.Foo`) or short label (`Foo`). */
    List<String> resolveTypeIds(String input) {
        return resolveType(input).ids();
    }

    SymbolResolution resolveType(String input) {
        if (input == null || input.isBlank()) return notFound(input, SymbolResolution.TargetKind.TYPE);
        // strip a trailing method-part if user passed `Foo#bar` to a type cmd
        String t = input;
        int hash = t.indexOf('#');
        if (hash >= 0) t = t.substring(0, hash);

        // Try exact qualified_name first.
        if (NodeKeyFactory.isKey(t)) {
            List<String> exact = runStringColumn("SELECT id FROM nodes WHERE id=?"
                    + selectorClause(), List.of(t));
            return exact(input, SymbolResolution.TargetKind.TYPE, exact);
        }
        String sql = "SELECT id FROM nodes WHERE qualified_name = ?" + selectorClause() + " AND kind IN ("
                + qmarks(GraphConstants.TYPE_KINDS.size()) + ")";
        List<Object> args = new ArrayList<>();
        args.add(t);
        args.addAll(GraphConstants.TYPE_KINDS);
        List<String> ids = runStringColumn(sql, args);
        if (!ids.isEmpty()) return exact(input, SymbolResolution.TargetKind.TYPE, ids);

        // Else label match
        sql = "SELECT id FROM nodes WHERE label = ?" + selectorClause() + " AND kind IN ("
                + qmarks(GraphConstants.TYPE_KINDS.size()) + ") ORDER BY qualified_name";
        args.clear();
        args.add(t);
        args.addAll(GraphConstants.TYPE_KINDS);
        return uniqueOrAmbiguous(input, SymbolResolution.TargetKind.TYPE,
                runStringColumn(sql, args));
    }

    /** Resolve a free-form input to one or more method node IDs.
     *  Accepts {@code pkg.Class#method}, {@code pkg.Class#method(p1,p2)},
     *  or {@code Class.method} / {@code method} shorthand. */
    List<String> resolveMethodIds(String input) {
        return resolveMethod(input).ids();
    }

    List<String> resolveMethodFamilyIds(String input) {
        return resolveMethod(input).requireFamilyOrExactIds();
    }

    String resolveUniqueMethodId(String input) {
        return resolveMethod(input).requireUnique().id;
    }

    String resolveExactMethodId(String input) {
        return resolveMethod(input).requireExact().id;
    }

    SymbolResolution resolveMethod(String input) {
        if (input == null || input.isBlank()) return notFound(input, SymbolResolution.TargetKind.METHOD);

        if (NodeKeyFactory.isKey(input)) {
            List<String> exact = runStringColumn(
                    "SELECT id FROM nodes WHERE kind IN (" + sqlIn(EXACT_CALLABLE_KINDS)
                            + ")" + selectorClause() + " AND id = ?", List.of(input));
            return exact(input, SymbolResolution.TargetKind.METHOD, exact);
        }

        // A selector containing parentheses is a signature contract. It never falls back.
        if (input.contains("(") || input.contains(")")) {
            if (!hasExplicitSignature(input)) {
                return notFound(input, SymbolResolution.TargetKind.METHOD);
            }
            List<String> exact = runStringColumn(
                    "SELECT id FROM nodes WHERE kind IN (" + sqlIn(EXACT_CALLABLE_KINDS)
                            + ")" + selectorClause() + " AND (id = ? OR symbol_id = ?)",
                    List.of(input, input));
            return exact(input, SymbolResolution.TargetKind.METHOD, exact);
        }

        // `pkg.Class#method` — match qualified_name exactly (any overload).
        if (input.contains("#")) {
            String[] parts = input.split("#", 2);
            String typePart = parts[0];
            String methodPart = parts[1];
            if (typePart.isBlank() || methodPart.isBlank()) {
                return notFound(input, SymbolResolution.TargetKind.METHOD);
            }

            if (typePart.contains(".")) {
                String q = typePart + "#" + methodPart;
                return family(input, runStringColumn(
                        "SELECT id FROM nodes WHERE kind IN (" + sqlIn(GraphConstants.METHOD_KINDS) + ") "
                      + selectorClause() + " AND qualified_name = ? ORDER BY id", List.of(q)));
            } else {
                // short class name
                String q = typePart + "#" + methodPart;
                return family(input, runStringColumn(
                        "SELECT id FROM nodes WHERE kind IN (" + sqlIn(GraphConstants.METHOD_KINDS) + ") "
                      + selectorClause() + " AND (qualified_name = ? OR qualified_name LIKE ? ESCAPE '\\') ORDER BY id",
                        List.of(q, likeSuffix("." + q))));
            }
        }

        // `Class.method` shorthand — split at last dot.
        int dot = input.lastIndexOf('.');
        if (dot > 0) {
            String typePart = input.substring(0, dot);
            String mname = input.substring(dot + 1);
            if (typePart.contains(".")) {
                return family(input, runStringColumn(
                        "SELECT id FROM nodes WHERE kind IN (" + sqlIn(GraphConstants.METHOD_KINDS) + ") "
                      + selectorClause() + " AND qualified_name = ? ORDER BY id",
                        List.of(typePart + "#" + mname)));
            } else {
                String q = typePart + "#" + mname;
                return family(input, runStringColumn(
                        "SELECT id FROM nodes WHERE kind IN (" + sqlIn(GraphConstants.METHOD_KINDS) + ") "
                      + selectorClause() + " AND (qualified_name = ? OR qualified_name LIKE ? ESCAPE '\\') ORDER BY id",
                        List.of(q, likeSuffix("." + q))));
            }
        }

        // bare method name
        return family(input, runStringColumn(
                "SELECT id FROM nodes WHERE kind IN (" + sqlIn(GraphConstants.METHOD_KINDS) + ")"
              + selectorClause() + " AND label = ? "
              + " ORDER BY qualified_name", List.of(input)));
    }

    /** Resolve a field reference. Accepts {@code pkg.Class#name},
     *  {@code Class.name}, or a bare {@code name} (matched by label). */
    List<String> resolveFieldIds(String input) {
        return resolveField(input).ids();
    }

    SymbolResolution resolveField(String input) {
        if (input == null || input.isBlank()) return notFound(input, SymbolResolution.TargetKind.FIELD);
        // pkg.Class#field — exact id (FIELD id = <classFqn>#<name>, no parens)
        if (input.contains("#")) {
            return exact(input, SymbolResolution.TargetKind.FIELD, runStringColumn(
                    "SELECT id FROM nodes WHERE kind='" + GraphConstants.Kind.FIELD
                            + "'" + selectorClause() + " AND (id = ? OR symbol_id = ?) ORDER BY id",
                    List.of(input, input)));
        }
        // Class.field — split at last dot; if typePart is qualified, exact match
        int dot = input.lastIndexOf('.');
        if (dot > 0) {
            String typePart = input.substring(0, dot);
            String fname = input.substring(dot + 1);
            if (typePart.contains(".")) {
                return exact(input, SymbolResolution.TargetKind.FIELD, runStringColumn(
                        "SELECT id FROM nodes WHERE kind='" + GraphConstants.Kind.FIELD
                                + "'" + selectorClause() + " AND symbol_id = ? ORDER BY id",
                        List.of(typePart + "#" + fname)));
            }
            String q = typePart + "#" + fname;
            return uniqueOrAmbiguous(input, SymbolResolution.TargetKind.FIELD, runStringColumn(
                    "SELECT id FROM nodes WHERE kind='" + GraphConstants.Kind.FIELD
                            + "'" + selectorClause()
                            + " AND (symbol_id = ? OR symbol_id LIKE ? ESCAPE '\\') ORDER BY id",
                    List.of(q, likeSuffix("." + q))));
        }
        // bare name — by label
        return uniqueOrAmbiguous(input, SymbolResolution.TargetKind.FIELD, runStringColumn(
                "SELECT id FROM nodes WHERE kind='" + GraphConstants.Kind.FIELD
                        + "'" + selectorClause() + " AND label = ? ORDER BY qualified_name",
                List.of(input)));
    }

    /** Resolve to a single NodeRow when caller wants one row (e.g. context). */
    NodeRow resolveNodeRow(String input) {
        SymbolResolution resolution = resolveNode(input);
        return resolution.status() == SymbolResolution.Status.NOT_FOUND
                ? null : resolution.requireUnique();
    }

    /** Resolve to every candidate in the same priority order as {@link #resolveNodeRow(String)}. */
    List<NodeRow> resolveNodeRows(String input) {
        return resolveNode(input).candidates();
    }

    SymbolResolution resolveNode(String input) {
        if (input == null || input.isBlank()) return notFound(input, SymbolResolution.TargetKind.NODE);
        List<String> exactNodeIds = runStringColumn("SELECT id FROM nodes WHERE id=?"
                + selectorClause(), List.of(input));
        if (!exactNodeIds.isEmpty()) {
            return retarget(exact(input, SymbolResolution.TargetKind.NODE, exactNodeIds),
                    SymbolResolution.TargetKind.NODE);
        }
        List<String> exactNames = runStringColumn("SELECT id FROM nodes WHERE qualified_name=?"
                + selectorClause() + " ORDER BY id", List.of(input));
        if (!exactNames.isEmpty()) {
            return retarget(uniqueOrAmbiguous(input, SymbolResolution.TargetKind.NODE, exactNames),
                    SymbolResolution.TargetKind.NODE);
        }
        if (input.contains("#") || input.contains("(") || input.contains(")")) {
            return retarget(resolveMethod(input), SymbolResolution.TargetKind.NODE);
        }
        SymbolResolution types = resolveType(input);
        if (types.status() != SymbolResolution.Status.NOT_FOUND) {
            return retarget(types, SymbolResolution.TargetKind.NODE);
        }
        return retarget(resolveMethod(input), SymbolResolution.TargetKind.NODE);
    }

    /** Candidate rows used only to scope evidence coverage; never performs fuzzy prefix matching. */
    List<NodeRow> resolveAnchorRows(String input) {
        if (input == null || input.isBlank()) return List.of();
        if (input.contains("#") || input.contains("(") || input.contains(")")) {
            SymbolResolution methods = resolveMethod(input);
            if (methods.status() != SymbolResolution.Status.NOT_FOUND) return methods.candidates();
            return resolveField(input).candidates();
        }
        SymbolResolution types = resolveType(input);
        if (types.status() != SymbolResolution.Status.NOT_FOUND) return types.candidates();
        SymbolResolution methods = resolveMethod(input);
        if (methods.status() != SymbolResolution.Status.NOT_FOUND) return methods.candidates();
        return resolveField(input).candidates();
    }

    NodeRow readNodeById(String id) {
        if (id == null) return null;
        if (nodeCache.containsKey(id)) return nodeCache.get(id);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + RowMappers.NODE_COLS + " FROM nodes n WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                NodeRow row = rs.next() ? RowMappers.mapNode(rs) : null;
                nodeCache.put(id, row);
                return row;
            }
        } catch (SQLException e) {
            throw new RuntimeException("query failed: " + e.getMessage(), e);
        }
    }

    private List<NodeRow> readNodesById(List<String> ids) {
        List<NodeRow> rows = new ArrayList<>();
        for (String id : ids) {
            NodeRow row = readNodeById(id);
            if (row != null) rows.add(row);
        }
        return rows;
    }

    private SymbolResolution exact(String input, SymbolResolution.TargetKind kind,
                                   List<String> ids) {
        if (ids.isEmpty()) return notFound(input, kind);
        List<NodeRow> rows = readNodesById(ids);
        return new SymbolResolution(input, kind,
                rows.size() == 1 ? SymbolResolution.Status.EXACT
                        : SymbolResolution.Status.AMBIGUOUS,
                rows);
    }

    private SymbolResolution uniqueOrAmbiguous(String input,
                                                SymbolResolution.TargetKind kind,
                                                List<String> ids) {
        if (ids.isEmpty()) return notFound(input, kind);
        List<NodeRow> rows = readNodesById(ids);
        return new SymbolResolution(input, kind,
                rows.size() == 1 ? SymbolResolution.Status.EXACT
                        : SymbolResolution.Status.AMBIGUOUS,
                rows);
    }

    private SymbolResolution family(String input, List<String> ids) {
        if (ids.isEmpty()) return notFound(input, SymbolResolution.TargetKind.METHOD);
        List<NodeRow> rows = readNodesById(ids);
        long owners = rows.stream()
                .map(node -> String.valueOf(node.module) + "\u0000"
                        + node.scope + "\u0000" + node.qualifiedName)
                .distinct().count();
        return new SymbolResolution(input, SymbolResolution.TargetKind.METHOD,
                owners == 1 ? SymbolResolution.Status.FAMILY
                        : SymbolResolution.Status.AMBIGUOUS,
                rows);
    }

    private static SymbolResolution notFound(String input,
                                             SymbolResolution.TargetKind kind) {
        return new SymbolResolution(input, kind, SymbolResolution.Status.NOT_FOUND, List.of());
    }

    private static SymbolResolution retarget(SymbolResolution resolution,
                                             SymbolResolution.TargetKind kind) {
        return new SymbolResolution(resolution.input(), kind,
                resolution.status(), resolution.candidates());
    }

    private static boolean hasExplicitSignature(String input) {
        int open = input.indexOf('(');
        return open > 0 && input.endsWith(")")
                && input.indexOf(')', open) == input.length() - 1
                && input.indexOf('(', open + 1) < 0;
    }

    private static String likeSuffix(String value) {
        return "%" + value.replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_");
    }

    void preloadNodes(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        List<String> toLoad = new ArrayList<>();
        for (String id : ids) {
            if (!nodeCache.containsKey(id)) toLoad.add(id);
        }
        if (toLoad.isEmpty()) return;
        for (int off = 0; off < toLoad.size(); off += 500) {
            List<String> batch = toLoad.subList(off, Math.min(off + 500, toLoad.size()));
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + RowMappers.NODE_COLS + " FROM nodes n WHERE id IN ("
                            + qmarks(batch.size()) + ")")) {
                bindStrings(ps, batch);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        NodeRow row = RowMappers.mapNode(rs);
                        nodeCache.put(row.id, row);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("query failed: " + e.getMessage(), e);
            }
            for (String id : batch) nodeCache.putIfAbsent(id, null);
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
            throw new RuntimeException("query failed: " + e.getMessage(), e);
        }
    }
}
