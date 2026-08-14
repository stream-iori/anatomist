package com.anatomist.query;

import com.anatomist.json.Json;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.store.FileCacheService;
import com.anatomist.store.IndexStateStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only, SQL-filtered declaration lookup. */
public final class DeclarationQueryService {
    private final Connection connection;

    public DeclarationQueryService(Connection connection) { this.connection = connection; }

    public void verifyFile(Path index, String file, String module, String scope) {
        IndexStateStore.Snapshot state = IndexStateStore.read(index);
        if (!state.fresh()) fail("INDEX_STALE", "index state is " + state.state().name().toLowerCase());
        try {
            if (exists("SELECT 1 FROM index_diagnostics WHERE code='JAVA_PARSE_FAILED' AND source_file=?", file)) {
                fail("FILE_PARSE_FAILED", "Java parsing failed for " + file);
            }
            StringBuilder cacheSql = new StringBuilder("SELECT hash FROM file_cache WHERE source_file=?");
            String cachedHash = scalar(cacheSql.toString(), file);
            if (cachedHash == null) fail("FILE_NOT_INDEXED", "file is not present in the committed index: " + file);

            String sourceRoot = scalar("SELECT value FROM project_meta WHERE key='source_root'");
            if (sourceRoot != null && !sourceRoot.isBlank()) {
                Path source = Path.of(sourceRoot).resolve(file).normalize();
                if (!source.startsWith(Path.of(sourceRoot).normalize()) || !Files.isRegularFile(source)) {
                    fail("INDEX_STALE", "indexed source file is missing: " + file);
                }
                if (!cachedHash.equals(FileCacheService.sha256(source))) {
                    fail("INDEX_STALE", "indexed source file has changed: " + file);
                }
            }

            StringBuilder dangling = new StringBuilder("SELECT 1 FROM declarations d WHERE d.source_file=? ")
                    .append("AND NOT EXISTS (SELECT 1 FROM nodes n WHERE n.symbol_id=d.symbol_id ")
                    .append("AND n.module=d.module AND n.scope=d.scope AND n.source_file=d.source_file)");
            List<String> args = new ArrayList<>(); args.add(file);
            appendSelection(dangling, args, module, scope, "d");
            dangling.append(" LIMIT 1");
            if (exists(dangling.toString(), args.toArray(String[]::new))) {
                fail("GRAPH_INTEGRITY_FAILED", "declaration symbol is not query-resolvable for " + file);
            }

            StringBuilder missing = new StringBuilder("SELECT 1 FROM nodes n WHERE n.source_file=? ")
                    .append("AND n.kind IN ('CLASS','INTERFACE','ENUM','ANNOTATION','RECORD','METHOD','CONSTRUCTOR') ")
                    .append("AND NOT EXISTS (SELECT 1 FROM declarations d WHERE d.symbol_id=n.symbol_id ")
                    .append("AND d.module=n.module AND d.scope=n.scope AND d.source_file=n.source_file)");
            List<String> missingArgs = new ArrayList<>(); missingArgs.add(file);
            appendSelection(missing, missingArgs, module, scope, "n");
            missing.append(" LIMIT 1");
            if (exists(missing.toString(), missingArgs.toArray(String[]::new))) {
                fail("DECLARATION_COVERAGE_INCOMPLETE", "indexed declaration coverage is incomplete for " + file);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Failed to verify declaration evidence", failure);
        }
    }

    public int count(String file, String module, String scope, Set<String> visibility,
                     Set<String> kinds, boolean topLevelTypes, boolean directMembers,
                     boolean includeSynthetic) {
        Query query = build("SELECT count(*)", file, module, scope, visibility, kinds,
                topLevelTypes, directMembers, includeSynthetic);
        try (PreparedStatement statement = prepare(query)) {
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getInt(1) : 0; }
        } catch (SQLException failure) { throw new RuntimeException("Failed to count declarations", failure); }
    }

    public List<DeclarationRow> find(String file, String module, String scope, Set<String> visibility,
                                     Set<String> kinds, boolean topLevelTypes, boolean directMembers,
                                     boolean includeSynthetic, int limit, int offset) {
        Query query = build("SELECT symbol_id,qualified_name,label,kind,declaration_kind,type_kind,visibility,"
                + "modifiers,declared_modifiers,implicit_modifiers,declaring_type,source_file,source_location,module,"
                + "scope,nesting_depth,direct_member,synthetic", file, module, scope, visibility, kinds,
                topLevelTypes, directMembers, includeSynthetic);
        query.sql.append(" ORDER BY CASE WHEN source_location GLOB 'L[0-9]*' THEN CAST(substr(source_location,2) AS INTEGER) "
                + "ELSE 2147483647 END, CASE declaration_kind WHEN 'type' THEN 0 WHEN 'constructor' THEN 1 ELSE 2 END, symbol_id"
                + " LIMIT ? OFFSET ?");
        query.args.add(limit); query.args.add(offset);
        List<DeclarationRow> out = new ArrayList<>();
        try (PreparedStatement statement = prepare(query); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) out.add(row(rows));
            return out;
        } catch (SQLException failure) { throw new RuntimeException("Failed to query declarations", failure); }
    }

    private static Query build(String select, String file, String module, String scope,
                               Set<String> visibility, Set<String> kinds, boolean topLevelTypes,
                               boolean directMembers, boolean includeSynthetic) {
        Query out = new Query(new StringBuilder(select + " FROM declarations d WHERE d.source_file=?"));
        out.args.add(file); appendSelection(out.sql, out.args, module, scope, "d");
        appendSet(out, "d.visibility", visibility);
        appendSet(out, "d.declaration_kind", kinds);
        if (topLevelTypes) out.sql.append(" AND (d.declaration_kind<>'type' OR d.nesting_depth=0)");
        if (directMembers) out.sql.append(" AND (d.declaration_kind='type' OR d.direct_member=1)");
        if (!includeSynthetic) out.sql.append(" AND d.synthetic=0");
        return out;
    }

    private static void appendSelection(StringBuilder sql, List<?> rawArgs, String module, String scope, String alias) {
        @SuppressWarnings("unchecked") List<Object> args = (List<Object>) rawArgs;
        if (module != null && !module.isBlank()) { sql.append(" AND ").append(alias).append(".module=?"); args.add(module); }
        if (scope != null && !"ALL".equals(scope)) { sql.append(" AND ").append(alias).append(".scope=?"); args.add(scope); }
    }

    private static void appendSet(Query query, String column, Set<String> values) {
        if (values == null || values.isEmpty()) return;
        query.sql.append(" AND ").append(column).append(" IN (")
                .append(String.join(",", java.util.Collections.nCopies(values.size(), "?"))).append(')');
        query.args.addAll(values.stream().sorted().toList());
    }

    private PreparedStatement prepare(Query query) throws SQLException {
        PreparedStatement out = connection.prepareStatement(query.sql.toString());
        for (int i = 0; i < query.args.size(); i++) {
            Object value = query.args.get(i);
            if (value instanceof Integer number) out.setInt(i + 1, number); else out.setString(i + 1, String.valueOf(value));
        }
        return out;
    }

    private boolean exists(String sql, String... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setString(i + 1, args[i]);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private String scalar(String sql, String... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setString(i + 1, args[i]);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        }
    }

    private static DeclarationRow row(ResultSet rows) throws SQLException {
        DeclarationRow out = new DeclarationRow(); int i = 1;
        out.symbolId = rows.getString(i++); out.qualifiedName = rows.getString(i++); out.label = rows.getString(i++);
        out.kind = rows.getString(i++); out.declarationKind = rows.getString(i++); out.typeKind = rows.getString(i++);
        out.visibility = rows.getString(i++); out.modifiers = strings(rows.getString(i++));
        out.declaredModifiers = strings(rows.getString(i++)); out.implicitModifiers = strings(rows.getString(i++));
        out.declaringType = rows.getString(i++); out.sourceFile = rows.getString(i++); out.sourceLocation = rows.getString(i++);
        out.module = rows.getString(i++); out.scope = rows.getString(i++); out.nestingDepth = rows.getInt(i++);
        out.directMember = rows.getInt(i++) != 0; out.synthetic = rows.getInt(i) != 0; return out;
    }

    private static List<String> strings(String json) {
        Object tree = Json.parseTree(json);
        if (!(tree instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private static void fail(String code, String message) { throw new DeclarationQueryException(code, message); }
    private record Query(StringBuilder sql, List<Object> args) {
        Query(StringBuilder sql) { this(sql, new ArrayList<>()); }
    }

    public static final class DeclarationQueryException extends RuntimeException {
        private final String code;
        public DeclarationQueryException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
