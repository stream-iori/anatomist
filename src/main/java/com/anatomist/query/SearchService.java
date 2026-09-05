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
import java.util.NoSuchElementException;
import java.util.Set;

import com.anatomist.query.semantic.SemanticCursor;
import static com.anatomist.query.QueryInfra.*;

public class SearchService {

    public enum SemanticMode { FTS, NAME, ANNOTATION }

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
        if (!containsFtsSyntax(ftsExpr)) ftsExpr = literalPrefix(ftsExpr);

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
                .append("WHERE COALESCE(a.annotation_fqn,a.raw_name) LIKE ? ");
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
                .append(" FROM nodes n WHERE n.label LIKE ? ESCAPE '\\' ");
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
        if (!containsFtsSyntax(ftsExpr)) ftsExpr = literalPrefix(ftsExpr);
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
                .append("WHERE COALESCE(a.annotation_fqn,a.raw_name) LIKE ? ");
        List<Object> args = new ArrayList<>();
        args.add("%" + annotationTerm.replace("@", "") + "%");
        sql.append(resolver.selectorClause("n")).append(' ');
        if (kind != null && !kind.isEmpty()) { sql.append("AND n.kind = ? "); args.add(kind); }
        return runScalarInt(conn, sql.toString(), args);
    }

    private static String globToLike(String glob) {
        if (glob == null) return "%";
        StringBuilder out = new StringBuilder(glob.length() + 8);
        for (int i = 0; i < glob.length(); i++) {
            char value = glob.charAt(i);
            switch (value) {
                case '*' -> out.append('%');
                case '?' -> out.append('_');
                case '\\', '%', '_' -> out.append('\\').append(value);
                default -> out.append(value);
            }
        }
        return out.toString();
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
                + "COALESCE(e.resolution, ?) AS resolution,e.confidence,e.producer_id,COUNT(*) AS edge_count "
                + "FROM (" + externalFactsSql() + ") e WHERE " + match + " "
                + "GROUP BY type_fqn,e.relation,COALESCE(e.resolution, ?),e.confidence,e.producer_id "
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
                    increment(row.producerCounts, result.getString("producer_id"), count);
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
        row.producerCounts = new LinkedHashMap<>();
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

    private static String literalPrefix(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"*";
    }

    private static boolean isAsciiRegexWhitespace(char character) {
        return character == ' ' || character == '\t' || character == '\n'
                || character == '\u000B' || character == '\f' || character == '\r';
    }

    /** Cursor used by semantic NDJSON search; legacy list queries keep their v2 behavior. */
    public SemanticCursor<NodeRow> semanticCursor(SemanticMode mode, String selector,
                                                   String semanticKind, int limit, int offset) {
        return semanticCursor(mode, selector, semanticKind, limit, offset, false);
    }

    public SemanticCursor<NodeRow> semanticCursor(SemanticMode mode, String selector,
                                                   String semanticKind, int limit, int offset,
                                                   boolean includeMeta) {
        List<String> kinds = semanticKinds(semanticKind);
        boolean externalOnly = GraphConstants.Kind.EXTERNAL_CLASS.equals(semanticKind);
        String filter = kinds.isEmpty() ? "" : " AND n.kind IN (" + qmarks(kinds.size()) + ")";
        String from;
        List<Object> baseArgs = new ArrayList<>();
        String order;
        if (mode == SemanticMode.FTS) {
            String expression = selector.trim();
            if (!containsFtsSyntax(expression)) expression = literalPrefix(expression);
            from = " FROM node_names nn JOIN nodes n ON nn.rowid=n.rowid "
                    + "WHERE node_names MATCH ? " + resolver.selectorClause("n") + filter;
            baseArgs.add(expression);
            order = " ORDER BY rank";
        } else if (mode == SemanticMode.NAME) {
            from = " FROM nodes n WHERE n.label LIKE ? ESCAPE '\\' "
                    + resolver.selectorClause("n") + filter;
            baseArgs.add(globToLike(selector));
            order = " ORDER BY n.qualified_name";
        } else if (includeMeta) {
            from = " FROM nodes n WHERE EXISTS (WITH RECURSIVE closure(name,depth,seen) AS ("
                    + "SELECT COALESCE(a.annotation_fqn,a.raw_name),0,'>'||COALESCE(a.annotation_fqn,a.raw_name)||'>' "
                    + "FROM annotations a WHERE a.node_id=n.id UNION ALL SELECT m.meta_annotation_fqn,c.depth+1,"
                    + "c.seen||m.meta_annotation_fqn||'>' FROM closure c JOIN (SELECT DISTINCT annotation_fqn,"
                    + "meta_annotation_fqn FROM annotation_meta_relations) m "
                    + "ON m.annotation_fqn=c.name WHERE c.depth<16 AND m.meta_annotation_fqn IS NOT NULL "
                    + "AND instr(c.seen,'>'||m.meta_annotation_fqn||'>')=0) "
                    + "SELECT 1 FROM closure WHERE name LIKE ?) " + resolver.selectorClause("n") + filter;
            baseArgs.add("%" + selector.replace("@", "") + "%");
            order = " ORDER BY n.qualified_name";
        } else {
            from = " FROM nodes n JOIN annotations a ON n.id=a.node_id "
                    + "WHERE COALESCE(a.annotation_fqn,a.raw_name) LIKE ? " + resolver.selectorClause("n") + filter;
            baseArgs.add("%" + selector.replace("@", "") + "%");
            order = " ORDER BY n.qualified_name";
        }
        baseArgs.addAll(kinds);
        int internalTotal = externalOnly ? 0 : runScalarInt(conn,
                "SELECT COUNT(" + (mode == SemanticMode.ANNOTATION && !includeMeta ? "DISTINCT n.id" : "*") + ")" + from,
                baseArgs);
        int internalOffset = Math.min(offset, internalTotal);
        int internalAvailable = Math.max(0, internalTotal - offset);
        int internalLimit = Math.min(limit, internalAvailable);
        SemanticCursor<NodeRow> internal = internalLimit == 0 ? emptyCursor()
                : nodeCursor("SELECT " + (mode == SemanticMode.ANNOTATION && !includeMeta ? "DISTINCT " : "")
                        + RowMappers.NODE_COLS + from + order + " LIMIT ? OFFSET ?",
                        withPage(baseArgs, internalLimit, internalOffset));

        boolean allowExternal = mode != SemanticMode.ANNOTATION && allowsSemanticExternal(semanticKind)
                && (mode != SemanticMode.FTS || !containsFtsSyntax(selector));
        int externalLimit = allowExternal ? Math.max(0, limit - internalLimit) : 0;
        int externalOffset = Math.max(0, offset - internalTotal);
        return new CompositeCursor(internal, () -> externalLimit == 0 ? emptyCursor()
                : externalCursor(mode, selector, externalLimit, externalOffset));
    }

    private SemanticCursor<NodeRow> externalCursor(SemanticMode mode, String selector,
                                                    int limit, int offset) {
        String type = "CASE WHEN instr(e.external_target_fqn,'#')>0 "
                + "THEN substr(e.external_target_fqn,1,instr(e.external_target_fqn,'#')-1) "
                + "ELSE e.external_target_fqn END";
        boolean name = mode == SemanticMode.NAME;
        String like = name ? globToLike(selector).toLowerCase(Locale.ROOT)
                : "%" + escapeLike(selector.trim().toLowerCase(Locale.ROOT)) + "%";
        String match = name
                ? "(lower(" + type + ") LIKE ? ESCAPE '\\' OR lower(" + type + ")=?)"
                : "lower(" + type + ") LIKE ? ESCAPE '\\'";
        String sql = "SELECT " + type + " type_fqn,count(*) edge_count FROM ("
                + externalFactsSql() + ") e WHERE " + match + " GROUP BY type_fqn "
                + "ORDER BY edge_count DESC,type_fqn LIMIT ? OFFSET ?";
        List<Object> args = new ArrayList<>();
        args.add(name ? "%." + like : like);
        if (name) args.add(like);
        args.add(limit);
        args.add(offset);
        try {
            PreparedStatement statement = conn.prepareStatement(sql);
            bind(statement, args);
            return new ResultCursor(statement, statement.executeQuery(), true);
        } catch (SQLException failure) {
            throw rethrow(failure);
        }
    }

    private SemanticCursor<NodeRow> nodeCursor(String sql, List<Object> args) {
        try {
            PreparedStatement statement = conn.prepareStatement(sql);
            bind(statement, args);
            return new ResultCursor(statement, statement.executeQuery(), false);
        } catch (SQLException failure) {
            throw rethrow(failure);
        }
    }

    private String externalFactsSql() {
        return "SELECT e.external_target_fqn,e.relation,e.resolution,e.confidence,e.producer_id "
                + "FROM edges e JOIN nodes src ON src.id=e.source_id WHERE e.is_external=1 "
                + resolver.selectorClause("src")
                + " UNION ALL SELECT cst.external_target_fqn,'CALLS',NULL,cst.confidence,cst.producer_id "
                + "FROM call_site_targets cst JOIN call_sites cs ON cs.site_pk=cst.call_site_pk "
                + "JOIN call_site_owners cso ON cso.owner_pk=cs.owner_pk "
                + "JOIN nodes src ON src.id=cso.caller_id WHERE cst.external_target_fqn IS NOT NULL "
                + resolver.selectorClause("src");
    }

    private static List<Object> withPage(List<Object> args, int limit, int offset) {
        List<Object> result = new ArrayList<>(args);
        result.add(limit);
        result.add(offset);
        return result;
    }

    private static List<String> semanticKinds(String kind) {
        if (kind == null || kind.isBlank() || "entity".equals(kind)) return List.of();
        return switch (kind) {
            case "type" -> List.of("CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION",
                    "ANONYMOUS_CLASS");
            case "callable" -> List.of("METHOD", "CONSTRUCTOR", "LAMBDA", "METHOD_REF");
            case "value" -> List.of("FIELD", "ENUM_CONSTANT");
            case "artifact" -> List.of("ARTIFACT");
            case "component" -> List.of("BEAN");
            case "config_entity" -> List.of("XML_PROPERTY", "XML_ENTRY", "XML_LIST", "XML_MAP",
                    "XML_VALUE", "XML_REF", "XML_IDREF", "XML_NULL", "XML_CONSTRUCTOR_ARG",
                    "XML_CALLABLE_REF");
            case GraphConstants.Kind.EXTERNAL_CLASS -> List.of();
            default -> List.of(kind);
        };
    }

    private static boolean allowsSemanticExternal(String kind) {
        return kind == null || kind.isBlank() || "entity".equals(kind) || "type".equals(kind)
                || GraphConstants.Kind.EXTERNAL_CLASS.equals(kind);
    }

    private static SemanticCursor<NodeRow> emptyCursor() {
        return new SemanticCursor<>() {
            @Override public boolean hasNext() { return false; }
            @Override public NodeRow next() { throw new NoSuchElementException(); }
            @Override public void close() {}
        };
    }

    private static final class ResultCursor implements SemanticCursor<NodeRow> {
        private final PreparedStatement statement;
        private final ResultSet rows;
        private final boolean external;
        private boolean positioned;
        private boolean closed;

        private ResultCursor(PreparedStatement statement, ResultSet rows, boolean external)
                throws SQLException {
            this.statement = statement;
            this.rows = rows;
            this.external = external;
            this.positioned = rows.next();
        }

        @Override public boolean hasNext() { return positioned && !closed; }

        @Override public NodeRow next() {
            if (!hasNext()) throw new NoSuchElementException();
            try {
                NodeRow row;
                if (external) {
                    String fqn = rows.getString("type_fqn");
                    row = new NodeRow();
                    row.id = fqn; row.symbolId = fqn; row.qualifiedName = fqn;
                    int dot = fqn.lastIndexOf('.');
                    row.label = dot < 0 ? fqn : fqn.substring(dot + 1);
                    row.kind = GraphConstants.Kind.EXTERNAL_CLASS;
                    row.externalTarget = true;
                    row.externalEdgeCount = rows.getLong("edge_count");
                } else row = RowMappers.mapNode(rows);
                positioned = rows.next();
                if (!positioned) close();
                return row;
            } catch (SQLException failure) {
                close();
                throw rethrow(failure);
            }
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            try { rows.close(); } catch (SQLException ignored) {}
            try { statement.close(); } catch (SQLException ignored) {}
        }
    }

    private final class CompositeCursor implements SemanticCursor<NodeRow> {
        private SemanticCursor<NodeRow> current;
        private final java.util.function.Supplier<SemanticCursor<NodeRow>> second;
        private boolean switched;

        private CompositeCursor(SemanticCursor<NodeRow> first,
                                java.util.function.Supplier<SemanticCursor<NodeRow>> second) {
            this.current = first;
            this.second = second;
        }

        @Override public boolean hasNext() {
            if (current.hasNext()) return true;
            if (!switched) {
                current.close();
                current = second.get();
                switched = true;
            }
            return current.hasNext();
        }

        @Override public NodeRow next() {
            if (!hasNext()) throw new NoSuchElementException();
            return current.next();
        }

        @Override public void close() { current.close(); }
    }

    public List<NodeRow> implementorsOf(String typeRef) {
        return implementorsOf(typeRef, false);
    }

    public List<NodeRow> implementorsOf(String typeRef, boolean recursive) {
        SymbolResolution resolution = resolver.resolveType(typeRef);
        List<String> targetIds = List.of(resolution.requireUnique().id);
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
        SymbolResolution resolution = resolver.resolveType(typeRef);
        List<String> targetIds = List.of(resolution.requireUnique().id);
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
