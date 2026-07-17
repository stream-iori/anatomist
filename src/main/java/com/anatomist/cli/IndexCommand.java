package com.anatomist.cli;

import com.anatomist.config.ConfigLoader;
import com.anatomist.config.ProjectConfig;
import com.anatomist.core.ClasspathDetector;
import com.anatomist.core.JavaParserFactory;
import com.anatomist.core.IndexTimings;
import com.anatomist.core.ProjectScanner;
import com.anatomist.core.logging.AnatomistLog;
import com.anatomist.store.FileCacheService;
import com.anatomist.store.IndexOperationLock;
import com.anatomist.incremental.IncrementalIndexer;
import com.anatomist.incremental.IncrementalParseException;
import com.anatomist.incremental.FullRebuildRequiredException;
import com.anatomist.incremental.PerformanceHistory;
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

    private static final Set<Path> GIT_CACHE_ADVISED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Parameters(index = "0", description = "Path to the Java project to index.")
    Path projectPath;

    @Option(names = "--java-version", description = "Target Java language version (default: 8).")
    Integer javaVersion;

    @Option(names = "--exclude", description = "Comma-separated directory names to exclude.")
    String exclude;

    @Option(names = "--output", description = "Output SQLite database path (default: ~/.anatomist/indexes/<repo-key>/index.db).")
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

    @Option(names = "--verify-content",
            description = "Hash every indexed file during standalone incremental change detection. "
                    + "By default unchanged size/mtime pairs reuse the cached hash; Watch events are always hashed.")
    boolean verifyContent;

    @Option(names = "--full", description = "Force full re-index (default behavior).")
    boolean full;

    @Option(names = "--recreate",
            description = "Delete the existing SQLite index and sidecar files before full indexing. "
                    + "Useful when schema or stale-table state is suspect. Disables incremental mode.")
    boolean recreate;

    @Option(names = "--max-realign-files",
            description = "Hard safety cap on the symbol-impact file set; above it, incremental degrades to full.",
            defaultValue = "1000")
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

    @Option(names = "--timings",
            description = "Include per-phase index timings in text/JSON output.")
    boolean timings;

    @Option(names = "--dataflow",
            description = "Build optional CFG/def-use/interprocedural flow facts.")
    boolean dataflow;

    @Option(names = "--implicit-taint",
            description = "Propagate taint through control dependencies. Implies --dataflow.")
    boolean implicitTaint;

    private IndexExecutionHints executionHints;
    private Path operationLockPath;
    private boolean deferFullFallback;
    private com.anatomist.core.JavaVersionDetection currentJavaVersionDetection;

    void setExecutionHints(IndexExecutionHints executionHints) {
        this.executionHints = executionHints;
    }

    /** Internal watch hook: replacement builds write a temporary DB but must
     * still serialize with writers of the live DB. */
    void setOperationLockPath(Path operationLockPath) {
        this.operationLockPath = operationLockPath;
    }

    void deferFullFallbackForWatch() {
        this.deferFullFallback = true;
    }

    boolean usesCandidateFastPathForTest() {
        return executionHints != null && executionHints.canUseFastPath();
    }

    @Override
    public Integer call() {
        return reportOutcome(executeOutcome());
    }

    com.anatomist.core.IndexOutcome executeOutcome() {
        return new com.anatomist.core.IndexApplicationService().execute(
                new com.anatomist.core.IndexRequest(projectPath, projectSource, sourceRootSpecs),
                root -> {
                    Path lockTarget = operationLockPath != null ? operationLockPath
                            : output == null ? DefaultIndexPath.forIndexWrite(root)
                            : output.toAbsolutePath().normalize();
                    try (IndexOperationLock ignored = IndexOperationLock.forWrite(lockTarget)) {
                        return execute(root);
                    }
                });
    }

    int reportOutcome(com.anatomist.core.IndexOutcome outcome) {
        if (outcome.error() != null) {
            System.err.println("ERROR: " + outcome.error());
            if (outcome.cause() != null
                    && !(outcome.cause() instanceof IncrementalParseException)) {
                outcome.cause().printStackTrace(System.err);
            }
        }
        return outcome.exitCode();
    }

    private int execute(Path projectRoot) throws Exception {
        long started = System.currentTimeMillis();
        long totalStarted = System.nanoTime();
        IndexTimings phaseTimings = new IndexTimings();
        ProjectConfig config = ConfigLoader.load(projectRoot);
        dataflow = dataflow || config.dataflow() || implicitTaint || config.implicitTaint();
        implicitTaint = implicitTaint || config.implicitTaint();
        if (externalExclude != null && !externalExclude.isBlank()) {
            config.addExternalExcludePatterns(Arrays.asList(externalExclude.split(",")));
        }

        Path home = DefaultIndexPath.resolveHome(
                System.getenv(DefaultIndexPath.ENV_HOME), System.getProperty("user.home"));
        AnatomistLog.configure(DefaultIndexPath.storageDir(projectRoot, home), home, debug);

        ClasspathDetector cd = new ClasspathDetector();

        long phaseStarted = phaseTimings.start();
        boolean candidateFastPath = executionHints != null && executionHints.canUseFastPath();
        List<Path> sourcePaths = candidateFastPath
                ? executionHints.sourceRoots().stream().map(com.anatomist.core.SourceRoot::path).toList()
                : resolveSourcePaths(cd, projectRoot);
        if (sourcePaths.isEmpty()) {
            System.err.println("ERROR: no source paths resolved for " + projectRoot);
            return 1;
        }
        List<com.anatomist.core.SourceRoot> resolvedSourceRoots = candidateFastPath
                ? executionHints.sourceRoots()
                : resolveSourceRoots(projectRoot, sourcePaths);

        Set<String> extraExcludes = exclude == null || exclude.isEmpty()
                ? Collections.emptySet()
                : new HashSet<>(Arrays.asList(exclude.split(",")));
        ProjectScanner scanner = new ProjectScanner(extraExcludes);
        List<Path> sourceFiles = candidateFastPath ? List.of() : scanner.scan(sourcePaths);
        phaseTimings.stop("discover", phaseStarted);
        if (!candidateFastPath && sourceFiles.isEmpty()) {
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
                if (deferFullFallback) throw new FullRebuildRequiredException("schema_version mismatch");
                IndexRuntime runtime = resolveRuntimeTimed(cd, projectRoot, sourcePaths, phaseTimings);
                return runFullIndex(projectRoot, sourcePaths, runtime.classpathEntries(),
                        sourceFilesForFull(scanner, sourcePaths, sourceFiles),
                        runtime.javaVersion(), runtime.factory(), dbPath, classpath, started, config, true,
                        phaseTimings, totalStarted);
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
                    if (deferFullFallback) throw new FullRebuildRequiredException(reason);
                    IndexRuntime runtime = resolveRuntimeTimed(cd, projectRoot, sourcePaths, phaseTimings);
                    return runFullIndex(projectRoot, sourcePaths, runtime.classpathEntries(),
                            sourceFilesForFull(scanner, sourcePaths, sourceFiles),
                            runtime.javaVersion(), runtime.factory(), dbPath, classpath, started, config, false,
                            phaseTimings, totalStarted);
                }
                String expectedLayoutHash = sourceLayoutHash(resolvedSourceRoots);
                if (!expectedLayoutHash.equals(store.readProjectMeta("source_layout_hash").orElse(""))) {
                    System.err.println("INFO: incremental degraded to full (source layout changed)");
                    if (deferFullFallback) throw new FullRebuildRequiredException("source layout changed");
                    IndexRuntime runtime = resolveRuntimeTimed(cd, projectRoot, sourcePaths, phaseTimings);
                    return runFullIndex(projectRoot, sourcePaths, runtime.classpathEntries(),
                            sourceFilesForFull(scanner, sourcePaths, sourceFiles),
                            runtime.javaVersion(), runtime.factory(), dbPath, classpath, started, config, false,
                            phaseTimings, totalStarted);
                }
                boolean priorDataflow = Boolean.parseBoolean(
                        store.readProjectMeta("dataflow").orElse("false"));
                boolean priorImplicitTaint = Boolean.parseBoolean(
                        store.readProjectMeta("implicit_taint").orElse("false"));
                if (priorDataflow != dataflow || priorImplicitTaint != implicitTaint) {
                    String reason = "flow profile changed";
                    System.err.println("INFO: incremental degraded to full (" + reason + ")");
                    if (deferFullFallback) throw new FullRebuildRequiredException(reason);
                    IndexRuntime runtime = resolveRuntimeTimed(cd, projectRoot, sourcePaths, phaseTimings);
                    return runFullIndex(projectRoot, sourcePaths, runtime.classpathEntries(),
                            sourceFilesForFull(scanner, sourcePaths, sourceFiles),
                            runtime.javaVersion(), runtime.factory(), dbPath, classpath, started, config, false,
                            phaseTimings, totalStarted);
                }
                com.anatomist.core.JavaVersionDetection currentVersion =
                        resolveJavaVersion(cd, projectRoot);
                int priorVersion;
                try {
                    priorVersion = Integer.parseInt(
                            store.readProjectMeta("java_version").orElse(""));
                } catch (NumberFormatException missingVersion) {
                    priorVersion = -1;
                }
                boolean reusePriorExplicitVersion =
                        currentVersion.source()
                                == com.anatomist.core.JavaVersionDetection.Source.FALLBACK
                        && "cli".equalsIgnoreCase(
                                store.readProjectMeta("java_version_source").orElse(""));
                if (!reusePriorExplicitVersion && currentVersion.version() != priorVersion) {
                    String reason = "java version changed";
                    System.err.println("INFO: incremental degraded to full (" + reason + ")");
                    if (deferFullFallback) throw new FullRebuildRequiredException(reason);
                    IndexRuntime runtime = resolveRuntimeTimed(cd, projectRoot, sourcePaths, phaseTimings);
                    return runFullIndex(projectRoot, sourcePaths, runtime.classpathEntries(),
                            sourceFilesForFull(scanner, sourcePaths, sourceFiles),
                            runtime.javaVersion(), runtime.factory(), dbPath, classpath, started, config, false,
                            phaseTimings, totalStarted);
                }
                com.anatomist.core.ProjectMetadata.GitSnapshotTask gitTask =
                        com.anatomist.core.ProjectMetadata.startIncrementalGitRead(
                                projectRoot, store.readProjectMeta(), fingerprintCache());
                phaseStarted = phaseTimings.start();
                FileCacheService fcs = new FileCacheService();
                java.util.Map<String, String> diskHashes;
                FileCacheService.Changes ch;
                if (executionHints != null && executionHints.canUseFastPath()) {
                    Set<String> candidates = filterCandidateFiles(
                            projectRoot, sourcePaths, executionHints.candidateFiles());
                    FileCacheService.CandidateScan candidateScan = fcs.detectCandidateChanges(
                            projectRoot, candidates, cache, springXml, phaseTimings);
                    diskHashes = candidateScan.diskHashes();
                    ch = candidateScan.changes();
                    if (!candidateScan.statRefreshes().isEmpty()) {
                        store.updateFileCache(candidateScan.statRefreshes());
                    }
                } else {
                    List<Path> hashTargets = sourceFiles;
                    if (springXml) {
                        List<Path> xmlFiles = scanner.scanSpringXml(projectRoot);
                        if (!xmlFiles.isEmpty()) {
                            hashTargets = new ArrayList<>(sourceFiles);
                            hashTargets.addAll(xmlFiles);
                        }
                    }
                    FileCacheService.CandidateScan scan = fcs.detectChangesFast(
                            projectRoot, hashTargets, cache, verifyContent, phaseTimings);
                    diskHashes = scan.diskHashes();
                    ch = scan.changes();
                    if (!scan.statRefreshes().isEmpty()) store.updateFileCache(scan.statRefreshes());
                }
                phaseTimings.stop("change_detection", phaseStarted);
                if (ch.isEmpty()) {
                    IncrementalIndexer.Summary summary = new IncrementalIndexer.Summary();
                    for (com.anatomist.core.IndexDiagnostic diagnostic : store.readIndexDiagnostics()) {
                        if ("UNRESOLVED_SYMBOLS".equals(diagnostic.code())) {
                            summary.unresolvedSymbols += diagnostic.count();
                        } else if ("DANGLING_FACTS_DROPPED".equals(diagnostic.code())) {
                            summary.droppedDanglingFacts += (int) diagnostic.count();
                        }
                    }
                    phaseStarted = phaseTimings.start();
                    com.anatomist.core.ProjectMetadata.WriteResult metadataResult =
                            com.anatomist.core.ProjectMetadata.writeIncremental(
                            store, projectRoot, sourcePaths, resolvedSourceRoots,
                            Integer.parseInt(store.readProjectMeta("java_version").orElse("8")),
                            store.readProjectMeta("classpath_mode").orElse(classpathMode()),
                            parsePathList(store.readProjectMeta("classpath_entries").orElse("")),
                            store.readProjectMeta("classpath_override").orElse(""), springXml,
                            cache, fingerprintCache(), phaseTimings, gitTask);
                    phaseTimings.stop("metadata", phaseStarted);
                    maybeAdviseGitCache(projectRoot, metadataResult);
                    store.upsertProjectMeta(java.util.Map.of(
                            "dataflow", String.valueOf(dataflow),
                            "implicit_taint", String.valueOf(implicitTaint)));
                    phaseTimings.stop("total", totalStarted);
                    long elapsed = System.currentTimeMillis() - started;
                    com.anatomist.core.IndexHealthReport persistedHealth =
                            com.anatomist.core.IndexHealthService.read(store);
                    IndexOutput.emitIncremental(format, projectRoot, dbPath, javaFileCount(cache),
                            summary, cache.size(), elapsed,
                            timings ? phaseTimings.millis() : java.util.Map.of(), persistedHealth);
                    if (strictHealth && persistedHealth.status()
                            != com.anatomist.core.IndexHealthReport.Status.HEALTHY) return 3;
                    return 0;
                }

                phaseStarted = phaseTimings.start();
                IndexRuntime runtime = resolveIncrementalRuntime(cd, projectRoot, sourcePaths, store);
                phaseTimings.stop("runtime", phaseStarted);

                IncrementalIndexer ii = new IncrementalIndexer(
                        projectRoot, sourcePaths, runtime.factory(), store, runtime.javaVersion(),
                        maxRealignFiles, springXml, config, resolvedSourceRoots,
                        executionHints == null ? null : executionHints.springXmlFiles(), phaseTimings,
                        executionHints == null ? null : executionHints.incrementalSession(),
                        dataflow, implicitTaint);
                IncrementalIndexer.Summary summary = ii.indexIncremental(
                        ch.changed, ch.added, ch.deleted, diskHashes);

                if (summary.degradedToFull) {
                    System.err.println("INFO: incremental degraded to full ("
                            + summary.degradationReason + ")");
                    if (deferFullFallback) {
                        throw new FullRebuildRequiredException(summary.degradationReason);
                    }
                    return runFullIndex(projectRoot, sourcePaths, runtime.classpathEntries(),
                            sourceFilesForFull(scanner, sourcePaths, sourceFiles),
                            runtime.javaVersion(), runtime.factory(), dbPath, classpath, started, config, false,
                            phaseTimings, totalStarted);
                }

                java.util.Map<String, FileCacheEntry> after = store.readFileCache();
                phaseStarted = phaseTimings.start();
                com.anatomist.core.ProjectMetadata.WriteResult metadataResult =
                        com.anatomist.core.ProjectMetadata.writeIncremental(
                        store, projectRoot, sourcePaths, resolvedSourceRoots,
                        runtime.javaVersion(), runtime.classpathMode(), runtime.classpathEntries(),
                        classpath, springXml, after, fingerprintCache(), phaseTimings, gitTask);
                phaseTimings.stop("metadata", phaseStarted);
                maybeAdviseGitCache(projectRoot, metadataResult);
                store.upsertProjectMeta(java.util.Map.of(
                        "dataflow", String.valueOf(dataflow),
                        "implicit_taint", String.valueOf(implicitTaint)));
                phaseTimings.stop("total", totalStarted);
                long variableMs = phaseTimings.millis().getOrDefault("parse_extract", 0L)
                        + phaseTimings.millis().getOrDefault("stage_write", 0L)
                        + phaseTimings.millis().getOrDefault("stage_promote", 0L)
                        + phaseTimings.millis().getOrDefault("impact_analysis", 0L);
                PerformanceHistory.recordIncremental(store, summary.reparsedFiles,
                        variableMs, phaseTimings.millis().getOrDefault("total", 0L));
                long elapsed = System.currentTimeMillis() - started;
                com.anatomist.core.IndexHealthReport persistedHealth =
                        com.anatomist.core.IndexHealthService.read(store);
                IndexOutput.emitIncremental(format, projectRoot, dbPath, javaFileCount(after),
                        summary, after.size(), elapsed,
                        timings ? phaseTimings.millis() : java.util.Map.of(), persistedHealth);
                if (strictHealth && persistedHealth.status()
                        != com.anatomist.core.IndexHealthReport.Status.HEALTHY) return 3;
                return 0;
            }
        }

        IndexRuntime runtime = resolveRuntimeTimed(cd, projectRoot, sourcePaths, phaseTimings);
        return runFullIndex(projectRoot, sourcePaths, runtime.classpathEntries(), sourceFiles,
                runtime.javaVersion(), runtime.factory(), dbPath, classpath, started, config, recreate,
                phaseTimings, totalStarted);
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
                                 boolean recreateDb,
                                 IndexTimings phaseTimings,
                                 long totalStarted) throws Exception {
        if (executionHints != null && executionHints.incrementalSession() != null) {
            executionHints.incrementalSession().invalidateKnownNodeIds();
        }
        com.anatomist.core.IndexConfig cfg = new com.anatomist.core.IndexConfig(
                projectRoot, sourcePaths, classpathEntries, sourceFiles,
                jv, springXml, config, dbPath, classpathOverride, noClasspath, debug,
                resolveSourceRoots(projectRoot, sourcePaths), strictHealth,
                factory == null ? null : currentJavaVersionDetection,
                dataflow, implicitTaint);
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
                long fullIndexStarted = phaseTimings.start();
                com.anatomist.core.IndexResult result;
                try {
                    result = orchestrator.run(store, phaseTimings);
                } catch (com.anatomist.core.StrictHealthException rejected) {
                    phaseTimings.stop("full_index", fullIndexStarted);
                    if ("json".equalsIgnoreCase(format)) {
                        IndexOutput.emitStrictParseFailure(dbPath, rejected.parseInventory());
                    } else {
                        System.err.println("ERROR: " + rejected.getMessage());
                        rejected.parseInventory().failures().forEach((file, problems) ->
                                System.err.println("  " + file + ": "
                                        + (problems.isEmpty() ? "parse failed" : problems.get(0))));
                    }
                    return 3;
                }
                phaseTimings.stop("full_index", fullIndexStarted);
                PerformanceHistory.recordFull(store,
                        phaseTimings.millis().getOrDefault("full_index", 0L),
                        sourceFiles.size());
                phaseTimings.stop("total", totalStarted);
                if ("json".equalsIgnoreCase(format)) {
                    IndexOutput.emitFullJson(result, cfg,
                            timings ? phaseTimings.millis() : java.util.Map.of());
                } else {
                    com.anatomist.core.IndexStatsPrinter.print(result, cfg, System.out);
                    if (timings) IndexOutput.emitTimingsText(phaseTimings.millis());
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

    private IndexRuntime resolveRuntimeTimed(ClasspathDetector cd,
                                             Path projectRoot,
                                             List<Path> sourcePaths,
                                             IndexTimings phaseTimings) {
        long phaseStarted = phaseTimings.start();
        IndexRuntime runtime = resolveRuntime(cd, projectRoot, sourcePaths);
        phaseTimings.stop("runtime", phaseStarted);
        return runtime;
    }

    private static void recreateIndexFiles(Path dbPath) throws java.io.IOException {
        Files.deleteIfExists(dbPath.resolveSibling(dbPath.getFileName() + "-wal"));
        Files.deleteIfExists(dbPath.resolveSibling(dbPath.getFileName() + "-shm"));
        Files.deleteIfExists(dbPath.resolveSibling(dbPath.getFileName() + "-journal"));
        Files.deleteIfExists(dbPath);
    }

    private IndexRuntime resolveRuntime(ClasspathDetector cd, Path projectRoot, List<Path> sourcePaths) {
        com.anatomist.core.JavaVersionDetection detected = resolveJavaVersion(cd, projectRoot);
        currentJavaVersionDetection = detected;
        boolean willDetectClasspath = !noClasspath && (classpath == null || classpath.isEmpty());
        if (willDetectClasspath) {
            System.err.println("Detecting classpath via Maven (this can take a while)...");
        }
        List<Path> classpathEntries = resolveClasspath(cd, projectRoot);
        int jv = detected.version();
        System.err.println("Parsing with Java " + jv);
        JavaParserFactory factory = new JavaParserFactory(
                jv, classpathEntries, sourcePaths, vmClasspath,
                executionHints == null ? null : executionHints.parserSessions());
        return new IndexRuntime(classpathEntries, jv, factory, classpathMode());
    }

    private com.anatomist.core.JavaVersionDetection resolveJavaVersion(
            ClasspathDetector cd, Path projectRoot) {
        com.anatomist.core.JavaVersionDetection detected;
        if (javaVersion != null) {
            detected = new com.anatomist.core.JavaVersionDetection(
                    javaVersion,
                    com.anatomist.core.JavaVersionDetection.Source.CLI,
                    null, "--java-version=" + javaVersion, java.util.List.of());
        } else {
            ProjectConfig loaded = ConfigLoader.load(projectRoot);
            if (loaded.hasJavaVersion()) {
                detected = new com.anatomist.core.JavaVersionDetection(
                        loaded.javaVersion(),
                        com.anatomist.core.JavaVersionDetection.Source.CONFIG,
                        projectRoot.resolve(".anatomist/config.toml"),
                        "index.java_version=" + loaded.javaVersion(), java.util.List.of());
            } else {
                detected = cd.detectJavaVersionDetailed(projectRoot);
            }
        }
        if (!detected.found()) {
            java.util.List<com.anatomist.core.IndexDiagnostic> diagnostics =
                    new java.util.ArrayList<>(detected.diagnostics());
            diagnostics.add(new com.anatomist.core.IndexDiagnostic(
                    "info", "JAVA_VERSION_FALLBACK", "JAVA_VERSION",
                    null, null, null, null, 1,
                    "No static Java version declaration was found; using Java 8."));
            detected = new com.anatomist.core.JavaVersionDetection(
                    8, com.anatomist.core.JavaVersionDetection.Source.FALLBACK,
                    null, "default=8", diagnostics);
        }
        if (!detected.supported()) {
            int exit = detected.source() == com.anatomist.core.JavaVersionDetection.Source.CLI
                    || detected.source() == com.anatomist.core.JavaVersionDetection.Source.CONFIG
                    ? 2 : 3;
            throw new com.anatomist.core.JavaVersionException(exit,
                    "JAVA_VERSION_UNSUPPORTED: Java " + detected.version()
                            + " is outside the supported analysis range 8..17"
                            + (detected.evidenceFile() == null ? ""
                            : " (" + detected.evidenceFile() + ")"));
        }
        return detected;
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
        if (noClasspath || (classpath != null && !classpath.isEmpty()) || javaVersion != null) return null;
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
        if (cachedJavaVersion < 8 || cachedJavaVersion > 17) {
            throw new com.anatomist.core.JavaVersionException(3,
                    "JAVA_VERSION_UNSUPPORTED: cached Java " + cachedJavaVersion
                            + " is outside the supported analysis range 8..17");
        }
        List<Path> cachedClasspath = parsePathList(store.readProjectMeta("classpath_entries").orElse(""));
        JavaParserFactory factory = new JavaParserFactory(
                cachedJavaVersion, cachedClasspath, sourcePaths, vmClasspath,
                executionHints == null ? null : executionHints.parserSessions());
        currentJavaVersionDetection = new com.anatomist.core.JavaVersionDetection(
                cachedJavaVersion, com.anatomist.core.JavaVersionDetection.Source.MAVEN,
                null, "project_meta.java_version=" + cachedJavaVersion, java.util.List.of());
        return new IndexRuntime(cachedClasspath, cachedJavaVersion, factory, "detected");
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

    private static int javaFileCount(java.util.Map<String, FileCacheEntry> cache) {
        if (cache == null || cache.isEmpty()) return 0;
        return (int) cache.keySet().stream().filter(path -> path.endsWith(".java")).count();
    }

    private static List<Path> sourceFilesForFull(ProjectScanner scanner,
                                                 List<Path> sourcePaths,
                                                 List<Path> discovered) throws java.io.IOException {
        return discovered.isEmpty() ? scanner.scan(sourcePaths) : discovered;
    }

    private static String classpathFingerprint(List<Path> classpathEntries, String override) {
        if (override != null && !override.isEmpty()) return override;
        if (classpathEntries == null || classpathEntries.isEmpty()) return "";
        List<String> sorted = classpathEntries.stream()
                .map(Path::toString).sorted().collect(Collectors.toList());
        return String.join(File.pathSeparator, sorted);
    }

    private static String sourceLayoutHash(List<com.anatomist.core.SourceRoot> sourceRoots) {
        String value = sourceRoots.stream()
                .map(r -> r.module() + "@" + r.scope() + "=" + r.path().toAbsolutePath().normalize())
                .sorted().collect(java.util.stream.Collectors.joining("\n"));
        return FileCacheService.sha256OfString(value);
    }

    private com.anatomist.core.ProjectMetadata.FingerprintCache fingerprintCache() {
        return executionHints == null ? null : executionHints.fingerprintCache();
    }

    private void maybeAdviseGitCache(
            Path projectRoot, com.anatomist.core.ProjectMetadata.WriteResult result) {
        if (!timings || result == null || result.gitStatusMillis() < 100) return;
        Path normalized = projectRoot.toAbsolutePath().normalize();
        if (!GIT_CACHE_ADVISED.add(normalized)) return;
        com.anatomist.core.ProjectMetadata.GitUntrackedCache state =
                com.anatomist.core.ProjectMetadata.gitUntrackedCache(normalized);
        if (state == com.anatomist.core.ProjectMetadata.GitUntrackedCache.ENABLED) return;
        System.err.println("INFO: Git untracked cache is " + state.value()
                + "; metadata_git can be faster after `git config core.untrackedCache true`");
    }

    private Set<String> filterCandidateFiles(Path projectRoot,
                                             List<Path> sourcePaths,
                                             Set<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return Set.of();
        Path root = projectRoot.toAbsolutePath().normalize();
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) continue;
            Path supplied = Path.of(candidate);
            Path absolute = supplied.isAbsolute() ? supplied : root.resolve(supplied);
            absolute = absolute.toAbsolutePath().normalize();
            boolean javaUnderSource = candidate.endsWith(".java")
                    && sourcePaths.stream().map(p -> p.toAbsolutePath().normalize())
                    .anyMatch(absolute::startsWith);
            boolean xml = springXml && candidate.endsWith(".xml") && absolute.startsWith(root);
            if (javaUnderSource || xml) out.add(candidate);
        }
        return out;
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
