package com.anatomist.cli;

import com.anatomist.config.ConfigLoader;
import com.anatomist.config.ProjectConfig;
import com.anatomist.core.ClasspathDetector;
import com.anatomist.core.ExtractionContext;
import com.anatomist.core.JavaParserFactory;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.core.ProjectScanner;
import com.anatomist.core.SpringBeanParser;
import com.anatomist.core.logging.AnatomistLog;
import com.anatomist.extract.AnnotationExtractor;
import com.anatomist.extract.CallGraphExtractor;
import com.anatomist.extract.FieldAccessExtractor;
import com.anatomist.extract.FieldExtractor;
import com.anatomist.extract.HierarchyExtractor;
import com.anatomist.extract.MethodExtractor;
import com.anatomist.extract.ReferenceExtractor;
import com.anatomist.extract.TypeExtractor;
import com.anatomist.extract.XmlBeanExtractor;
import com.anatomist.incremental.FileCacheService;
import com.anatomist.json.Json;
import com.anatomist.incremental.IncrementalIndexer;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.semantic.SemanticPostProcessor;
import com.anatomist.store.SqliteStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(
        name = "index",
        mixinStandardHelpOptions = true,
        description = "Index a Java project into a SQLite database. Use --format json for a stable Agent summary."
)
public class IndexCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the Java project to index.")
    Path projectPath;

    @Option(names = "--java-version", description = "Target Java language version (default: 8).")
    Integer javaVersion;

    @Option(names = "--exclude", description = "Comma-separated directory names to exclude.")
    String exclude;

    @Option(names = "--output", description = "Output SQLite database path (default: ~/.anatomist/<repo>/index.db).")
    Path output;

    @Option(names = "--classpath", description = "Override classpath entries (path-separator delimited).")
    String classpath;

    @Option(names = "--project-source",
            description = "Override project source roots (path-separator delimited). "
                    + "Usually unnecessary: when omitted, multi-module Maven "
                    + "projects have every module's src/main/java auto-discovered. "
                    + "Relative roots resolve against the project path argument.")
    String projectSource;

    @Option(names = "--include-tests",
            description = "Also index src/test/java (test-only modules included). "
                    + "Off by default — only main sources are indexed. Ignored when "
                    + "--project-source is given (you control the roots explicitly).")
    boolean includeTests;

    @Option(names = "--no-classpath", description = "Skip classpath detection; external types will be unresolved.")
    boolean noClasspath;

    @Option(names = "--vm-classpath",
            description = "Add ReflectionTypeSolver so JDK types resolve. "
                    + "Defaults to true so java.lang.* / java.util.* are visible "
                    + "without an explicit classpath. Turn off when analysing a "
                    + "much older target than the running JDK.",
            defaultValue = "true", arity = "1")
    boolean vmClasspath;

    @Option(names = "--incremental", description = "Incremental index: only re-parse changed files.")
    boolean incremental;

    @Option(names = "--full", description = "Force full re-index (default behavior).")
    boolean full;

    @Option(names = "--recreate",
            description = "Delete the existing SQLite index and sidecar files before full indexing. "
                    + "Useful when schema or stale-table state is suspect. Disables incremental mode.")
    boolean recreate;

    @Option(names = "--max-realign-files",
            description = "Cap on the realign closure size; above it, incremental degrades to full.",
            defaultValue = "200")
    int maxRealignFiles;

    @Option(names = "--spring-xml",
            description = "Also parse Spring bean XML (<beans>) configs into BEAN nodes "
                    + "+ DEFINED_BY / WIRES edges. Off by default.")
    boolean springXml;

    @Option(names = "--debug",
            description = "Write detailed diagnostics (classpath detection, symbol "
                    + "resolution failures, dropped dangling edges) to "
                    + "~/.anatomist/logs/debug.log. Off by default.")
    boolean debug;

    @Option(names = "--external-exclude",
            description = "Comma-separated FQN patterns to exclude from external reference tracking "
                    + "(e.g. \"com.google.**,org.apache.**\"). Appends to config.toml [external].exclude_patterns.")
    String externalExclude;

    @Option(names = "--format", description = "Output format: text | json.", defaultValue = "text")
    String format;

    @Override
    public Integer call() {
        long started = System.currentTimeMillis();
        try {
            if (projectPath == null || !Files.isDirectory(projectPath)) {
                System.err.println("ERROR: project path does not exist or is not a directory: " + projectPath);
                return 1;
            }
            Path projectRoot = projectPath.toAbsolutePath().normalize();

            ProjectConfig config = ConfigLoader.load(projectRoot);
            if (externalExclude != null && !externalExclude.isBlank()) {
                config.addExternalExcludePatterns(Arrays.asList(externalExclude.split(",")));
            }

            Path home = DefaultIndexPath.resolveHome(
                    System.getenv(DefaultIndexPath.ENV_HOME), System.getProperty("user.home"));
            AnatomistLog.configure(home.resolve(DefaultIndexPath.repoNameOf(projectRoot)), home, debug);

            ClasspathDetector cd = new ClasspathDetector();

            List<Path> sourcePaths = resolveSourcePaths(cd, projectRoot);
            if (sourcePaths.isEmpty()) {
                System.err.println("ERROR: no source paths resolved for " + projectRoot);
                return 1;
            }

            boolean willDetectClasspath = !noClasspath && (classpath == null || classpath.isEmpty());
            if (willDetectClasspath) {
                System.err.println("Detecting classpath via Maven (this can take a while)...");
            }
            List<Path> classpathEntries = resolveClasspath(cd, projectRoot);

            Set<String> extraExcludes = exclude == null || exclude.isEmpty()
                    ? Collections.emptySet()
                    : new HashSet<>(Arrays.asList(exclude.split(",")));
            ProjectScanner scanner = new ProjectScanner(extraExcludes);
            List<Path> sourceFiles = scanner.scan(sourcePaths);
            if (sourceFiles.isEmpty()) {
                System.err.println("ERROR: no .java files found under " + sourcePaths);
                return 1;
            }

            int jv;
            if (javaVersion != null) {
                jv = javaVersion;
            } else {
                jv = cd.detectJavaVersion(projectRoot).orElse(8);
            }
            System.err.println("Parsing with Java " + jv);
            JavaParserFactory factory = new JavaParserFactory(
                    jv, classpathEntries, sourcePaths, vmClasspath);

            Path dbPath = output == null
                    ? DefaultIndexPath.forIndexWrite(projectRoot)
                    : output.toAbsolutePath().normalize();
            Files.createDirectories(dbPath.getParent());

            boolean useIncremental = incremental && !full && !recreate && Files.exists(dbPath);

            if (useIncremental) {
                try (com.anatomist.store.IndexLock wLock = com.anatomist.store.IndexLock.forWrite(dbPath);
                     SqliteStore store = new SqliteStore(dbPath)) {
                    java.util.Map<String, FileCacheEntry> cache;
                    try {
                        cache = store.readFileCache();
                    } catch (RuntimeException ex) {
                        // Schema missing — fall back to full
                        cache = java.util.Collections.emptyMap();
                    }
                    boolean schemaMismatch = !cache.isEmpty() && cache.values().stream()
                            .anyMatch(e -> e.schemaVersion() != FileCacheService.CURRENT_SCHEMA_VERSION);
                    if (cache.isEmpty() || schemaMismatch) {
                        String reason = cache.isEmpty()
                                ? "file_cache empty"
                                : "schema_version mismatch";
                        System.err.println("INFO: incremental degraded to full (" + reason + ")");
                        return runFullIndex(projectRoot, sourcePaths, classpathEntries, sourceFiles,
                                jv, factory, dbPath, classpath, started, config, false);
                    }
                    FileCacheService fcs = new FileCacheService();
                    List<Path> hashTargets = sourceFiles;
                    if (springXml) {
                        List<Path> xmlFiles = scanner.scanSpringXml(projectRoot);
                        if (!xmlFiles.isEmpty()) {
                            hashTargets = new ArrayList<>(sourceFiles);
                            hashTargets.addAll(xmlFiles);
                        }
                    }
                    java.util.Map<String, String> diskHashes = fcs.computeFileHashes(projectRoot, hashTargets);
                    FileCacheService.Changes ch = fcs.detectChanges(diskHashes, cache);

                    IncrementalIndexer ii = new IncrementalIndexer(
                            projectRoot, sourcePaths, factory, store, jv,
                            maxRealignFiles, springXml, config);
                    IncrementalIndexer.Summary summary = ii.indexIncremental(
                            ch.changed, ch.added, ch.deleted, diskHashes);

                    if (summary.degradedToFull) {
                        System.err.println("INFO: incremental degraded to full ("
                                + summary.degradationReason + ")");
                        return runFullIndex(projectRoot, sourcePaths, classpathEntries, sourceFiles,
                                jv, factory, dbPath, classpath, started, config, false);
                    }

                    long elapsed = System.currentTimeMillis() - started;
                    java.util.Map<String, FileCacheEntry> after = store.readFileCache();
                    if ("json".equalsIgnoreCase(format)) {
                        emitIncrementalJson(projectRoot, dbPath, sourceFiles.size(), summary, after.size(), elapsed);
                    } else {
                        System.out.println("Indexed " + projectRoot + " (incremental)");
                        System.out.println("  Changed files: " + summary.changedFiles);
                        System.out.println("  New files:     " + summary.newFiles);
                        System.out.println("  Deleted files: " + summary.deletedFiles);
                        System.out.println("  Realigned deps:" + summary.realignedDependents);
                        System.out.println("  New nodes:     " + summary.newNodes);
                        System.out.println("  New edges:     " + summary.newEdges);
                        System.out.println("  Output:        " + dbPath);
                        System.out.println("  File cache:    " + after.size() + " entries");
                        System.out.println("Done in " + elapsed + "ms");
                    }
                    return 0;
                }
            }

            return runFullIndex(projectRoot, sourcePaths, classpathEntries, sourceFiles,
                    jv, factory, dbPath, classpath, started, config, recreate);
        } catch (Exception e) {
            System.err.println("ERROR: index failed: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    private Integer runFullIndex(Path projectRoot,
                                 List<Path> sourcePaths,
                                 List<Path> classpathEntries,
                                 List<Path> sourceFiles,
                                 int jv,
                                 JavaParserFactory factory,
                                 Path dbPath,
                                 String classpathOverride,
                                 long started,
                                 ProjectConfig config,
                                 boolean recreateDb) throws Exception {
        com.anatomist.core.IndexConfig cfg = new com.anatomist.core.IndexConfig(
                projectRoot, sourcePaths, classpathEntries, sourceFiles,
                jv, springXml, config, dbPath, classpathOverride, debug);
        com.anatomist.core.IndexOrchestrator orchestrator =
                new com.anatomist.core.IndexOrchestrator(cfg, factory);

        try (com.anatomist.store.IndexLock wLock = com.anatomist.store.IndexLock.forWrite(dbPath)) {
            if (recreateDb) {
                recreateIndexFiles(dbPath);
            }
            try (SqliteStore store = new SqliteStore(dbPath)) {
                com.anatomist.core.IndexResult result = orchestrator.run(store);
                if ("json".equalsIgnoreCase(format)) {
                    emitIndexJson(result, cfg);
                } else {
                    com.anatomist.core.IndexStatsPrinter.print(result, cfg, System.out);
                }
                if (!"json".equalsIgnoreCase(format) && result.samplingEnabled()
                        && result.unresolvedSamples() != null) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> samples = result.unresolvedSamples();
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Long> sampleData = (java.util.Map<String, Long>) samples.get("samples");
                    @SuppressWarnings("unchecked")
                    java.util.Set<String> projectPackages = (java.util.Set<String>) samples.get("projectPackages");
                    long unresolvedCount = ((Number) samples.get("unresolvedCount")).longValue();
                    com.anatomist.core.UnresolvedReporter.print(
                            System.out, sampleData, projectPackages, unresolvedCount);
                }
            }
        }
        return 0;
    }

    private static void recreateIndexFiles(Path dbPath) throws java.io.IOException {
        Files.deleteIfExists(dbPath.resolveSibling(dbPath.getFileName() + "-wal"));
        Files.deleteIfExists(dbPath.resolveSibling(dbPath.getFileName() + "-shm"));
        Files.deleteIfExists(dbPath.resolveSibling(dbPath.getFileName() + "-journal"));
        Files.deleteIfExists(dbPath);
    }

    private void emitIndexJson(com.anatomist.core.IndexResult result,
                               com.anatomist.core.IndexConfig cfg) {
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        java.util.Map<String, Long> kinds = result.kindCounts();
        java.util.Map<String, Long> relations = result.relationCounts();
        long types = kinds.entrySet().stream()
                .filter(e -> GraphConstants.INDEX_SUMMARY_TYPE_KINDS.contains(e.getKey()))
                .mapToLong(java.util.Map.Entry::getValue)
                .sum();
        stats.put("source_files", cfg.sourceFiles().size());
        stats.put("types", types);
        stats.put("classes", kinds.getOrDefault(GraphConstants.Kind.CLASS, 0L));
        stats.put("methods", kinds.getOrDefault(GraphConstants.Kind.METHOD, 0L));
        stats.put("fields", kinds.getOrDefault(GraphConstants.Kind.FIELD, 0L));
        stats.put("beans", kinds.getOrDefault(GraphConstants.Kind.BEAN, 0L));
        stats.put("unresolved", result.unresolvedCount());
        stats.put("dropped_dangling_edges", result.droppedDanglingEdges());
        stats.put("file_cache_entries", result.fileCacheSize());
        stats.put("elapsed_ms", result.elapsedMs());

        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("command", "index");
        out.put("status", "ok");
        out.put("schema_version", FileCacheService.CURRENT_SCHEMA_VERSION);
        out.put("index_path", cfg.dbPath().toString());
        out.put("stats", stats);
        out.put("node_kinds", kinds);
        out.put("relations", relations);
        out.put("warnings", java.util.List.of());
        out.put("errors", java.util.List.of());
        System.out.println(Json.writePretty(out));
    }

    private void emitIncrementalJson(Path projectRoot,
                                     Path dbPath,
                                     int sourceFileCount,
                                     IncrementalIndexer.Summary summary,
                                     int fileCacheSize,
                                     long elapsedMs) {
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("source_files", sourceFileCount);
        stats.put("changed_files", summary.changedFiles);
        stats.put("new_files", summary.newFiles);
        stats.put("deleted_files", summary.deletedFiles);
        stats.put("realigned_dependents", summary.realignedDependents);
        stats.put("new_nodes", summary.newNodes);
        stats.put("new_edges", summary.newEdges);
        stats.put("file_cache_entries", fileCacheSize);
        stats.put("elapsed_ms", elapsedMs);

        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("command", "index");
        out.put("status", "ok");
        out.put("mode", "incremental");
        out.put("schema_version", FileCacheService.CURRENT_SCHEMA_VERSION);
        out.put("project_root", projectRoot.toString());
        out.put("index_path", dbPath.toString());
        out.put("stats", stats);
        out.put("warnings", java.util.List.of());
        out.put("errors", java.util.List.of());
        System.out.println(Json.writePretty(out));
    }

    List<Path> resolveSourcePaths(ClasspathDetector cd, Path projectRoot) {
        if (projectSource != null && !projectSource.isEmpty()) {
            List<Path> out = new ArrayList<>();
            for (String p : projectSource.split(File.pathSeparator)) {
                String t = p.trim();
                if (t.isEmpty()) continue;
                Path resolved = Path.of(t);
                if (!resolved.isAbsolute()) resolved = projectRoot.resolve(t);
                out.add(resolved);
            }
            return out;
        }
        return cd.detectSourcePaths(projectRoot, includeTests);
    }

    List<Path> resolveClasspath(ClasspathDetector cd, Path projectRoot) {
        if (noClasspath) return Collections.emptyList();
        java.util.LinkedHashSet<Path> out = new java.util.LinkedHashSet<>();
        if (classpath != null && !classpath.isEmpty()) {
            Arrays.stream(classpath.split(File.pathSeparator))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(Path::of)
                    .forEach(out::add);
            out.addAll(cd.detectBuildOutputClasspath(projectRoot));
            return new ArrayList<>(out);
        }
        return cd.detect(projectRoot).stream().map(Path::of).collect(Collectors.toList());
    }

}
