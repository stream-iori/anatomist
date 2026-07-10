package com.anatomist.cli;

import com.anatomist.config.ConfigLoader;
import com.anatomist.config.ProjectConfig;
import com.anatomist.core.ClasspathDetector;
import com.anatomist.core.JavaParserFactory;
import com.anatomist.core.ProjectScanner;
import com.anatomist.core.logging.AnatomistLog;
import com.anatomist.store.FileCacheService;
import com.anatomist.incremental.IncrementalIndexer;
import com.anatomist.model.FileCacheEntry;
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

    @Option(names = "--source-root",
            description = "Explicit source identity: <module>@<MAIN|TEST|GENERATED>=<path>. Repeatable.")
    List<String> sourceRootSpecs = new ArrayList<>();

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

    @Option(names = "--strict-health",
            description = "Return exit code 3 when the completed index health is not HEALTHY.")
    boolean strictHealth;

    @Override
    public Integer call() {
        com.anatomist.core.IndexOutcome outcome = new com.anatomist.core.IndexApplicationService().execute(
                new com.anatomist.core.IndexRequest(projectPath, projectSource, sourceRootSpecs),
                this::execute);
        if (outcome.error() != null) {
            System.err.println("ERROR: " + outcome.error());
            if (outcome.cause() != null) outcome.cause().printStackTrace(System.err);
        }
        return outcome.exitCode();
    }

    private int execute(Path projectRoot) throws Exception {
        long started = System.currentTimeMillis();
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

        Set<String> extraExcludes = exclude == null || exclude.isEmpty()
                ? Collections.emptySet()
                : new HashSet<>(Arrays.asList(exclude.split(",")));
        ProjectScanner scanner = new ProjectScanner(extraExcludes);
        List<Path> sourceFiles = scanner.scan(sourcePaths);
        if (sourceFiles.isEmpty()) {
            System.err.println("ERROR: no .java files found under " + sourcePaths);
            return 1;
        }

        Path dbPath = output == null
                ? DefaultIndexPath.forIndexWrite(projectRoot)
                : output.toAbsolutePath().normalize();
        Files.createDirectories(dbPath.getParent());

        boolean useIncremental = incremental && !full && !recreate && Files.exists(dbPath);

        if (useIncremental) {
            boolean schemaIncompatible;
            try (com.anatomist.store.IndexLock wLock = com.anatomist.store.IndexLock.forWrite(dbPath);
                 SqliteStore store = new SqliteStore(dbPath)) {
                schemaIncompatible = store.schemaExists() && !store.schemaCompatible();
            }
            if (schemaIncompatible) {
                System.err.println("INFO: incremental degraded to full (schema_version mismatch)");
                IndexRuntime runtime = resolveRuntime(cd, projectRoot, sourcePaths);
                return runFullIndex(projectRoot, sourcePaths, runtime.classpathEntries(), sourceFiles,
                        runtime.javaVersion(), runtime.factory(), dbPath, classpath, started, config, true);
            }

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
                    IndexRuntime runtime = resolveRuntime(cd, projectRoot, sourcePaths);
                    return runFullIndex(projectRoot, sourcePaths, runtime.classpathEntries(), sourceFiles,
                            runtime.javaVersion(), runtime.factory(), dbPath, classpath, started, config, false);
                }
                String expectedLayoutHash = sourceLayoutHash(projectRoot, sourcePaths);
                if (!expectedLayoutHash.equals(store.readProjectMeta("source_layout_hash").orElse(""))) {
                    System.err.println("INFO: incremental degraded to full (source layout changed)");
                    IndexRuntime runtime = resolveRuntime(cd, projectRoot, sourcePaths);
                    return runFullIndex(projectRoot, sourcePaths, runtime.classpathEntries(), sourceFiles,
                            runtime.javaVersion(), runtime.factory(), dbPath, classpath, started, config, false);
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
                if (ch.isEmpty()) {
                    IncrementalIndexer.Summary summary = new IncrementalIndexer.Summary();
                    for (com.anatomist.core.IndexDiagnostic diagnostic : store.readIndexDiagnostics()) {
                        if ("UNRESOLVED_SYMBOLS".equals(diagnostic.code())) {
                            summary.unresolvedSymbols += diagnostic.count();
                        } else if ("DANGLING_FACTS_DROPPED".equals(diagnostic.code())) {
                            summary.droppedDanglingFacts += (int) diagnostic.count();
                        }
                    }
                    long elapsed = System.currentTimeMillis() - started;
                        IndexOutput.emitIncremental(format, projectRoot, dbPath, sourceFiles.size(),
                                summary, cache.size(), elapsed);
                    if (strictHealth && com.anatomist.core.IndexHealthService
                            .fromCounts(summary.unresolvedSymbols, summary.droppedDanglingFacts).status()
                            != com.anatomist.core.IndexHealthReport.Status.HEALTHY) return 3;
                    return 0;
                }

                IndexRuntime runtime = resolveIncrementalRuntime(cd, projectRoot, sourcePaths, store);

                IncrementalIndexer ii = new IncrementalIndexer(
                        projectRoot, sourcePaths, runtime.factory(), store, runtime.javaVersion(),
                        maxRealignFiles, springXml, config, resolveSourceRoots(projectRoot, sourcePaths));
                IncrementalIndexer.Summary summary = ii.indexIncremental(
                        ch.changed, ch.added, ch.deleted, diskHashes);

                if (summary.degradedToFull) {
                    System.err.println("INFO: incremental degraded to full ("
                            + summary.degradationReason + ")");
                    return runFullIndex(projectRoot, sourcePaths, runtime.classpathEntries(), sourceFiles,
                            runtime.javaVersion(), runtime.factory(), dbPath, classpath, started, config, false);
                }

                long elapsed = System.currentTimeMillis() - started;
                java.util.Map<String, FileCacheEntry> after = store.readFileCache();
                writeRuntimeMetadata(store, projectRoot, sourcePaths, runtime);
                    IndexOutput.emitIncremental(format, projectRoot, dbPath, sourceFiles.size(),
                            summary, after.size(), elapsed);
                if (strictHealth && com.anatomist.core.IndexHealthService
                        .fromCounts(summary.unresolvedSymbols, summary.droppedDanglingFacts).status()
                        != com.anatomist.core.IndexHealthReport.Status.HEALTHY) return 3;
                return 0;
            }
        }

        IndexRuntime runtime = resolveRuntime(cd, projectRoot, sourcePaths);
        return runFullIndex(projectRoot, sourcePaths, runtime.classpathEntries(), sourceFiles,
                runtime.javaVersion(), runtime.factory(), dbPath, classpath, started, config, recreate);
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
                jv, springXml, config, dbPath, classpathOverride, noClasspath, debug,
                resolveSourceRoots(projectRoot, sourcePaths));
        com.anatomist.core.IndexOrchestrator orchestrator =
                new com.anatomist.core.IndexOrchestrator(cfg, factory);

        try (com.anatomist.store.IndexLock wLock = com.anatomist.store.IndexLock.forWrite(dbPath)) {
            boolean shouldRecreate = recreateDb;
            if (!shouldRecreate && Files.exists(dbPath)) {
                try (SqliteStore probe = new SqliteStore(dbPath)) {
                    shouldRecreate = probe.schemaExists() && !probe.schemaCompatible();
                }
                if (shouldRecreate) {
                    System.err.println("INFO: recreating incompatible index schema at " + dbPath);
                }
            }
            if (shouldRecreate) {
                recreateIndexFiles(dbPath);
            }
            try (SqliteStore store = new SqliteStore(dbPath)) {
                com.anatomist.core.IndexResult result = orchestrator.run(store);
                if ("json".equalsIgnoreCase(format)) {
                    IndexOutput.emitFullJson(result, cfg);
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
                if (strictHealth && com.anatomist.core.IndexHealthService.fromResult(result).status()
                        != com.anatomist.core.IndexHealthReport.Status.HEALTHY) {
                    return 3;
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

    private IndexRuntime resolveRuntime(ClasspathDetector cd, Path projectRoot, List<Path> sourcePaths) {
        boolean willDetectClasspath = !noClasspath && (classpath == null || classpath.isEmpty());
        if (willDetectClasspath) {
            System.err.println("Detecting classpath via Maven (this can take a while)...");
        }
        List<Path> classpathEntries = resolveClasspath(cd, projectRoot);
        int jv = javaVersion != null
                ? javaVersion
                : cd.detectJavaVersion(projectRoot).orElse(8);
        System.err.println("Parsing with Java " + jv);
        JavaParserFactory factory = new JavaParserFactory(
                jv, classpathEntries, sourcePaths, vmClasspath);
        return new IndexRuntime(classpathEntries, jv, factory, classpathMode());
    }

    private IndexRuntime resolveIncrementalRuntime(ClasspathDetector cd,
                                                   Path projectRoot,
                                                   List<Path> sourcePaths,
                                                   SqliteStore store) {
        IndexRuntime cached = cachedDetectedRuntime(projectRoot, sourcePaths, store);
        if (cached != null) {
            System.err.println("Parsing with Java " + cached.javaVersion());
            return cached;
        }
        return resolveRuntime(cd, projectRoot, sourcePaths);
    }

    private IndexRuntime cachedDetectedRuntime(Path projectRoot, List<Path> sourcePaths, SqliteStore store) {
        if (noClasspath || (classpath != null && !classpath.isEmpty())) return null;
        if (!"detected".equals(store.readProjectMeta("classpath_mode").orElse(null))) return null;
        String expectedRoot = projectRoot.toAbsolutePath().normalize().toString();
        if (!expectedRoot.equals(store.readProjectMeta("source_root").orElse(null))) return null;
        String expectedSourcePaths = joinPaths(sourcePaths);
        if (!expectedSourcePaths.equals(store.readProjectMeta("source_paths").orElse(null))) return null;
        int cachedJavaVersion;
        try {
            cachedJavaVersion = Integer.parseInt(store.readProjectMeta("java_version").orElse(""));
        } catch (NumberFormatException ex) {
            return null;
        }
        List<Path> cachedClasspath = parsePathList(store.readProjectMeta("classpath_entries").orElse(""));
        JavaParserFactory factory = new JavaParserFactory(
                cachedJavaVersion, cachedClasspath, sourcePaths, vmClasspath);
        return new IndexRuntime(cachedClasspath, cachedJavaVersion, factory, "detected");
    }

    private void writeRuntimeMetadata(SqliteStore store,
                                      Path projectRoot,
                                      List<Path> sourcePaths,
                                      IndexRuntime runtime) {
        store.upsertProjectMeta("source_root", projectRoot.toAbsolutePath().normalize().toString());
        store.upsertProjectMeta("source_paths", joinPaths(sourcePaths));
        store.upsertProjectMeta("java_version", String.valueOf(runtime.javaVersion()));
        store.upsertProjectMeta("classpath_mode", runtime.classpathMode());
        store.upsertProjectMeta("classpath_entries", joinPaths(runtime.classpathEntries()));
        store.upsertProjectMeta("classpath_override", classpath == null ? "" : classpath);
        store.upsertProjectMeta("classpath_hash",
                FileCacheService.sha256OfString(classpathFingerprint(runtime.classpathEntries(), classpath)));
    }

    private String classpathMode() {
        if (noClasspath) return "none";
        if (classpath != null && !classpath.isBlank()) return "explicit";
        return "detected";
    }

    private static String joinPaths(List<Path> paths) {
        if (paths == null || paths.isEmpty()) return "";
        return String.join(File.pathSeparator, paths.stream()
                .map(p -> p.toAbsolutePath().normalize().toString())
                .toList());
    }

    private static List<Path> parsePathList(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Path::of)
                .toList();
    }

    private static String classpathFingerprint(List<Path> classpathEntries, String override) {
        if (override != null && !override.isEmpty()) return override;
        if (classpathEntries == null || classpathEntries.isEmpty()) return "";
        List<String> sorted = classpathEntries.stream()
                .map(Path::toString).sorted().collect(Collectors.toList());
        return String.join(File.pathSeparator, sorted);
    }

    private String sourceLayoutHash(Path projectRoot, List<Path> sourcePaths) {
        String value = resolveSourceRoots(projectRoot, sourcePaths).stream()
                .map(r -> r.module() + "@" + r.scope() + "=" + r.path().toAbsolutePath().normalize())
                .sorted().collect(java.util.stream.Collectors.joining("\n"));
        return FileCacheService.sha256OfString(value);
    }

    private record IndexRuntime(List<Path> classpathEntries,
                                int javaVersion,
                                JavaParserFactory factory,
                                String classpathMode) {}

    List<Path> resolveSourcePaths(ClasspathDetector cd, Path projectRoot) {
        if (!sourceRootSpecs.isEmpty()) {
            return resolveSourceRoots(projectRoot, List.of()).stream()
                    .map(com.anatomist.core.SourceRoot::path).toList();
        }
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

    List<com.anatomist.core.SourceRoot> resolveSourceRoots(Path projectRoot, List<Path> sourcePaths) {
        if (sourceRootSpecs.isEmpty()) {
            return com.anatomist.core.SourceIdentityResolver.inferRoots(projectRoot, sourcePaths);
        }
        List<com.anatomist.core.SourceRoot> roots = new ArrayList<>();
        for (String spec : sourceRootSpecs) {
            int at = spec.indexOf('@');
            int eq = spec.indexOf('=', at + 1);
            if (at <= 0 || eq <= at + 1 || eq == spec.length() - 1) {
                throw new IllegalArgumentException("invalid --source-root '" + spec
                        + "' (expected module@scope=path)");
            }
            String module = spec.substring(0, at);
            com.anatomist.core.SourceScope scope;
            try {
                scope = com.anatomist.core.SourceScope.valueOf(
                        spec.substring(at + 1, eq).toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("invalid source scope in --source-root: " + spec);
            }
            Path path = Path.of(spec.substring(eq + 1));
            if (!path.isAbsolute()) path = projectRoot.resolve(path);
            roots.add(new com.anatomist.core.SourceRoot(path, module, scope));
        }
        return roots;
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
