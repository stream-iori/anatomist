package com.anatomist.flow;

import com.anatomist.core.HealthPolicy;
import com.anatomist.core.IndexHealthService;
import com.anatomist.core.JavaParserFactory;
import com.anatomist.core.ParseInventory;
import com.anatomist.core.SourceRoot;
import com.anatomist.core.SourceScope;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.query.EdgeRow;
import com.anatomist.query.QueryService;
import com.anatomist.query.TraversalResult;
import com.anatomist.store.FileCacheService;
import com.anatomist.store.IndexLock;
import com.anatomist.store.SqliteStore;

import java.io.File;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Materializes DETAIL flow facts only for the source files on one static call path. */
public final class FlowMaterializer {

    public record Result(List<EdgeRow> callPath, int selectedFiles, int selectedMethods,
                         FlowPersistence.Stats stats, boolean noOp) {}

    private final Path database;

    public FlowMaterializer(Path database) {
        this.database = database.toAbsolutePath().normalize();
    }

    public Result materialize(String source, String target, int depth,
                              boolean throughCallbacks, String module, String scope,
                              boolean verifyContent) {
        TraversalResult<EdgeRow> traversal;
        try (QueryService query = new QueryService(database)) {
            query.selectNodes(module, scope);
            traversal = query.callPathTraversal(source, target, depth, throughCallbacks);
        }
        if (traversal.depthTruncated()) {
            throw new FlowMaterializationException(
                    "FLOW_MATERIALIZATION_DEPTH_TRUNCATED",
                    "static call path reached the requested depth; increase --depth before materializing");
        }
        if (traversal.items().isEmpty()) {
            throw new FlowMaterializationException("FLOW_MATERIALIZATION_PATH_UNRESOLVED",
                    "no static project call path exists between the selected endpoints");
        }

        Set<String> methodIds = methodIds(traversal.items());
        if (methodIds.isEmpty()) {
            throw new FlowMaterializationException("FLOW_MATERIALIZATION_PATH_UNRESOLVED",
                    "the static path contains no materializable project methods");
        }
        try (IndexLock ignored = IndexLock.forWrite(database);
             SqliteStore store = new SqliteStore(database)) {
            if (!store.schemaCompatible()) {
                throw new FlowMaterializationException("SCHEMA_MISMATCH",
                        "index schema is incompatible; re-index before materializing flow");
            }
            if (!IndexHealthService.read(store).gate(HealthPolicy.INTEGRITY).passed()) {
                throw new FlowMaterializationException("INDEX_INTEGRITY_FAILED",
                        "index integrity gate failed; repair the index before materializing flow");
            }
            Map<String, String> meta = store.readProjectMeta();
            Path root = requiredPath(meta, "source_root");
            Map<String, FileCacheEntry> cache = store.readFileCache();
            ensureFresh(root, cache, verifyContent);
            Map<String, String> sourceFiles = sourceFiles(store, methodIds);
            if (sourceFiles.size() != methodIds.size()) {
                throw new FlowMaterializationException("FLOW_MATERIALIZATION_EXTERNAL_ENDPOINT",
                        "the static path includes an external or missing-source method");
            }
            List<String> selectedFiles = sourceFiles.values().stream().distinct().sorted().toList();
            if (allDetailed(store, methodIds)) {
                return new Result(traversal.items(), selectedFiles.size(), methodIds.size(),
                        FlowPersistence.stats(store), true);
            }
            List<Path> sourcePaths = parsePaths(meta.get("source_paths"));
            List<SourceRoot> roots = parseRoots(meta.get("source_layout"));
            if (sourcePaths.isEmpty() || roots.isEmpty()) {
                throw new FlowMaterializationException("FLOW_MATERIALIZATION_PROFILE_INCOMPLETE",
                        "index is missing persisted source roots; rebuild the structural index first");
            }
            int javaVersion = parseJavaVersion(meta.get("java_version"));
            JavaParserFactory parser = new JavaParserFactory(javaVersion,
                    parsePaths(meta.get("classpath_entries")), sourcePaths, true);
            FlowAnalyzer analyzer = new FlowAnalyzer(root, sourcePaths, roots,
                    TaintRules.load(root), false, FlowProfile.full());
            FlowResult output = new FlowResult();
            List<Path> files = selectedFiles.stream().map(root::resolve).toList();
            ParseInventory inventory = parser.parseInventory(files, (path, unit) -> analyzer.analyze(unit, output));
            if (!inventory.complete()) {
                throw new FlowMaterializationException("FLOW_MATERIALIZATION_PARSE_FAILED",
                        "selected source files could not be parsed; fix source and re-index first");
            }
            FlowPersistence.Stats stats = FlowPersistence.replaceFiles(store, selectedFiles, output, null);
            return new Result(traversal.items(), selectedFiles.size(), methodIds.size(), stats, false);
        }
    }

    private static Set<String> methodIds(List<EdgeRow> path) {
        Set<String> ids = new LinkedHashSet<>();
        for (EdgeRow edge : path) {
            if (edge.source != null) ids.add(edge.source);
            if (edge.target != null) ids.add(edge.target);
        }
        return ids;
    }

    private static Map<String, String> sourceFiles(SqliteStore store, Collection<String> methods) {
        Map<String, String> out = new LinkedHashMap<>();
        String marks = String.join(",", java.util.Collections.nCopies(methods.size(), "?"));
        try (PreparedStatement statement = store.connection().prepareStatement(
                "SELECT id,source_file FROM nodes WHERE id IN (" + marks + ")")) {
            int index = 1;
            for (String method : methods) statement.setString(index++, method);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) out.put(rows.getString(1), rows.getString(2));
            }
        } catch (SQLException e) {
            throw new RuntimeException("failed to resolve materialization source files", e);
        }
        return out;
    }

    private static boolean allDetailed(SqliteStore store, Collection<String> methods) {
        String marks = String.join(",", java.util.Collections.nCopies(methods.size(), "?"));
        try (PreparedStatement statement = store.connection().prepareStatement(
                "SELECT count(*) FROM method_flow_coverage WHERE detail_level='DETAIL'"
                        + " AND method_id IN (" + marks + ")")) {
            int index = 1;
            for (String method : methods) statement.setString(index++, method);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) == methods.size();
            }
        } catch (SQLException e) {
            throw new RuntimeException("failed to inspect flow coverage", e);
        }
    }

    private static void ensureFresh(Path root, Map<String, FileCacheEntry> cache,
                                    boolean verifyContent) {
        List<Path> files = cache.keySet().stream().filter(path -> path.endsWith(".java"))
                .map(root::resolve).filter(java.nio.file.Files::isRegularFile).toList();
        FileCacheService.CandidateScan scan = new FileCacheService().detectChangesFast(
                root, files, cache, verifyContent, null);
        if (!scan.changes().isEmpty()) {
            throw new FlowMaterializationException("INDEX_STALE",
                    "source differs from the structural index; run the incremental index gate first");
        }
    }

    private static Path requiredPath(Map<String, String> meta, String key) {
        String value = meta.get(key);
        if (value == null || value.isBlank()) {
            throw new FlowMaterializationException("FLOW_MATERIALIZATION_PROFILE_INCOMPLETE",
                    "index is missing " + key + "; rebuild the structural index first");
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static List<Path> parsePaths(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<Path> out = new ArrayList<>();
        for (String raw : value.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!raw.isBlank()) out.add(Path.of(raw));
        }
        return List.copyOf(out);
    }

    private static List<SourceRoot> parseRoots(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<SourceRoot> out = new ArrayList<>();
        for (String line : value.split("\\R")) {
            int equals = line.indexOf('=');
            int at = line.indexOf('@');
            if (equals <= at || at <= 0) continue;
            try {
                out.add(new SourceRoot(Path.of(line.substring(equals + 1)),
                        line.substring(0, at), SourceScope.valueOf(line.substring(at + 1, equals))));
            } catch (IllegalArgumentException ignored) {
                // A corrupt layout is reported as an incomplete profile by the caller.
            }
        }
        return List.copyOf(out);
    }

    private static int parseJavaVersion(String value) {
        try {
            return Integer.parseInt(value);
        } catch (RuntimeException invalid) {
            throw new FlowMaterializationException("FLOW_MATERIALIZATION_PROFILE_INCOMPLETE",
                    "index is missing a valid java_version; rebuild the structural index first");
        }
    }
}
