package com.anatomist.cli;

import com.anatomist.config.ConfigLoader;
import com.anatomist.config.ConfigException;
import com.anatomist.config.LoadedConfig;
import com.anatomist.config.ProjectConfig;
import com.anatomist.core.ClasspathDetector;
import com.anatomist.core.JavaParserFactory;
import com.anatomist.core.IndexTimings;
import com.anatomist.core.ProjectScanner;
import com.anatomist.core.ScanPolicy;
import com.anatomist.core.SourceScope;
import com.anatomist.core.logging.AnatomistLog;
import com.anatomist.store.FileCacheService;
import com.anatomist.store.IndexOperationLock;
import com.anatomist.incremental.IncrementalIndexer;
import com.anatomist.incremental.IncrementalParseException;
import com.anatomist.incremental.PerformanceHistory;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.store.SqliteStore;
import com.anatomist.store.IndexFileSwap;
import com.anatomist.store.IndexLock;
import com.anatomist.store.IndexStateStore;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(
        name = "index",
        mixinStandardHelpOptions = true,
        description = "Index project sources with an installed language provider into SQLite. Use --format json for a stable Agent summary.",
        footer = {
                "",
                "Configuration: select .anatomist/config.toml, then ~/.anatomist/config.toml,",
                "otherwise built-in defaults. Files do not merge; missing keys use defaults,",
                "and CLI options override the selected profile.",
                "Lombok is off by default. Enable it per project in .anatomist/config.toml:",
                "  [extensions.lombok]",
                "  mode = \"ast\"",
                "Built-in scan: MAIN + GENERATED, include **, no scan exclude.",
                "Use doctor --format json --index <db> to inspect committed config_source,",
                "config_path, and scan_policy_hash."
        }
)
public class IndexCommand implements Callable<Integer> {

    private static final Set<Path> GIT_CACHE_ADVISED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Parameters(index = "0", description = "Path to the project to index.")
    Path projectPath;

    @Option(names = "--provider", defaultValue = "java-core",
            description = "Installed language provider ID (default: java-core).")
    String providerId;

    @Option(names = "--java-version",
            description = "Target Java language version. Precedence: CLI, config, Maven/Gradle, then Java 8.")
    Integer javaVersion;

    @Option(names = "--jdk-home", description = "Local target JDK home for native catalog resolution. "
            + "Defaults to ANATOMIST_JDK_HOME when set.")
    Path jdkHome;

    @Option(names = "--exclude", description = "Comma-separated directory names to exclude.")
    String exclude;

    @Option(names = "--output", description = "Output SQLite database path (default: ~/.anatomist/indexes/<repo-key>/index.db).")
    Path output;

    @Option(names = "--classpath", description = "Override classpath entries (path-separator delimited).")
    String classpath;

    @Option(names = "--project-source",
            description = "Override configured project source roots (path-separator delimited). "
                    + "Usually unnecessary: when omitted, multi-module Maven "
                    + "projects have every module's src/main/java auto-discovered. "
                    + "Relative roots resolve against the project path argument.")
    String projectSource;

    @Option(names = "--source-root",
            description = "Explicit source identity: <module>@<MAIN|TEST|GENERATED>=<path>. "
                    + "Replaces configured source roots. Repeatable.")
    List<String> sourceRootSpecs = new ArrayList<>();

    @Option(names = "--include-tests",
            description = "Add TEST to effective scan scopes (including test-only modules). "
                    + "Ignored when explicit source roots are selected.")
    boolean includeTests;

    @Option(names = "--scan-scope",
            description = "Source scope to index: MAIN, TEST, or GENERATED. Repeatable; replaces config scopes.")
    List<String> scanScopeSpecs = new ArrayList<>();

    @Option(names = "--scan-include",
            description = "Project-relative path glob to include. Repeatable; replaces config includes.")
    List<String> scanIncludeSpecs = new ArrayList<>();

    @Option(names = "--scan-exclude",
            description = "Project-relative path glob to exclude. Repeatable; replaces config excludes.")
    List<String> scanExcludeSpecs = new ArrayList<>();

    @Option(names = "--no-classpath", description = "Skip classpath detection; external types will be unresolved.")
    boolean noClasspath;

    @Option(names = "--vm-classpath",
            description = "Add ReflectionTypeSolver so JDK types resolve. "
                    + "Defaults to true so java.lang.* / java.util.* are visible "
                    + "without an explicit classpath. Turn off when analysing a "
                    + "much older target than the running JDK.",
            arity = "1")
    Boolean vmClasspath;

    @Option(names = "--incremental", description = "Incremental index: only re-parse changed files.")
    boolean incremental;

    @Option(names = "--verify-content",
            description = "Hash every indexed file during standalone incremental change detection. "
                    + "By default unchanged size/mtime pairs reuse the cached hash.")
    boolean verifyContent;

    @Option(names = "--changed-files-from", paramLabel = "<file|->",
            description = "Authoritative UTF-8 project-relative changed paths; '-' reads stdin. "
                    + "Blank lines and # comments are ignored.")
    String changedFilesFrom;

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

    @Option(names = "--spring-xml", negatable = true,
            description = "Also parse Spring bean XML (<beans>) configs into BEAN nodes "
                    + "+ DEFINED_BY / WIRES edges. Off by default.")
    Boolean springXml;

    @Option(names = "--lombok", description = "Lombok structural model: off | ast. AST mode adds signature evidence and structured edge lombok_usage; it does not run Lombok or read bytecode. Off by default; project config key: [extensions.lombok] mode.")
    String lombokMode;

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

    @Option(names = "--health-policy",
            description = "Health gate: none | integrity | complete. --strict-health aliases complete.")
    String healthPolicy;

    @Option(names = "--timings",
            description = "Include per-phase index timings in text/JSON output.")
    boolean timings;

    private com.anatomist.core.HealthPolicy effectiveHealthPolicy =
            com.anatomist.core.HealthPolicy.NONE;

    private com.anatomist.core.JavaVersionDetection currentJavaVersionDetection;
    private com.anatomist.core.ClasspathDetectionResult currentClasspathDetection =
            com.anatomist.core.ClasspathDetectionResult.notRequested();
    private LoadedConfig loadedConfig;
    private ScanPolicy scanPolicy;
    private List<SourceScope> effectiveScanScopes = List.of(SourceScope.MAIN, SourceScope.GENERATED);
    private boolean sourceRootsFromConfig;
    private boolean captureFullResult;
    private com.anatomist.core.IndexResult capturedFullResult;
    private com.anatomist.application.IndexConfig capturedFullConfig;
    private Map<String, Long> capturedFullTimings = Map.of();
    private Map<String, Object> capturedRebuild = Map.of();
    private com.anatomist.core.ParseInventory capturedStrictParseFailure;

    @Override
    public Integer call() {
        try {
            format = CliValidation.choice("--format", format, "text", "json");
            com.anatomist.core.HealthPolicy.resolve(strictHealth, healthPolicy);
        } catch (IllegalArgumentException invalid) {
            System.err.println("ERROR: " + invalid.getMessage());
            return 2;
        }
        return reportOutcome(executeOutcome());
    }

    com.anatomist.application.IndexOutcome executeOutcome() {
        return new com.anatomist.application.IndexApplicationService().execute(
                new com.anatomist.application.IndexRequest(projectPath, projectSource, sourceRootSpecs),
                root -> {
                    Path lockTarget = output == null ? DefaultIndexPath.forIndexWrite(root)
                            : output.toAbsolutePath().normalize();
                    try (IndexOperationLock ignored = IndexOperationLock.forWrite(lockTarget)) {
                        return execute(root);
                    }
                });
    }

    int reportOutcome(com.anatomist.application.IndexOutcome outcome) {
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
        loadedConfig = ConfigLoader.loadResolved(projectRoot);
        ProjectConfig config = loadedConfig.config();
        configureEffectiveOptions(projectRoot, config);
        effectiveHealthPolicy = com.anatomist.core.HealthPolicy.resolve(strictHealth, healthPolicy);
        if (config.lombokStrict()) {
            effectiveHealthPolicy = com.anatomist.core.HealthPolicy.COMPLETE;
        }
        if (externalExclude != null && !externalExclude.isBlank()) {
            config.addExternalExcludePatterns(Arrays.asList(externalExclude.split(",")));
        }

        Path home = DefaultIndexPath.resolveHome(
                System.getenv(DefaultIndexPath.ENV_HOME), System.getProperty("user.home"));
        AnatomistLog.configure(DefaultIndexPath.storageDir(projectRoot, home), home, debug);

        ClasspathDetector cd = new ClasspathDetector();

        long discoverStarted = phaseTimings.start();
        long phaseStarted = phaseTimings.start();
        List<Path> sourcePaths = resolveSourcePaths(cd, projectRoot);
        phaseTimings.stop("source_root_discovery", phaseStarted);
        if (sourcePaths.isEmpty()) {
            System.err.println("ERROR: no source paths resolved for " + projectRoot);
            return 1;
        }
        List<com.anatomist.core.SourceRoot> resolvedSourceRoots =
                resolveSourceRoots(projectRoot, sourcePaths);

        Set<String> extraExcludes = exclude == null || exclude.isEmpty()
                ? Collections.emptySet()
                : Arrays.stream(exclude.split(",")).map(String::trim)
                .filter(value -> !value.isEmpty()).collect(Collectors.toSet());
        scanPolicy = new ScanPolicy(projectRoot,
                scanIncludeSpecs.isEmpty() ? config.scanIncludes() : scanIncludeSpecs,
                scanExcludeSpecs.isEmpty() ? config.scanExcludes() : scanExcludeSpecs,
                extraExcludes);
        ProjectScanner scanner = new ProjectScanner(extraExcludes, scanPolicy);
        com.anatomist.provider.LanguageProvider languageProvider =
                com.anatomist.provider.LanguageProviderRegistry.builtIns()
                        .requireProvider(providerId);
        phaseStarted = phaseTimings.start();
        List<Path> sourceFiles = languageProvider.discover(scanner, resolvedSourceRoots).files();
        phaseTimings.stop("source_inventory", phaseStarted);
        phaseTimings.stop("discover", discoverStarted);
        if (sourceFiles.isEmpty()) {
            System.err.println("ERROR: no source files claimed by " + providerId
                    + " found under " + sourcePaths);
            return 1;
        }

        Path dbPath = output == null
                ? DefaultIndexPath.forIndexWrite(projectRoot)
                : output.toAbsolutePath().normalize();
        Files.createDirectories(dbPath.getParent());

        boolean requestedIncremental = incremental && !full && !recreate && Files.exists(dbPath);
        phaseStarted = phaseTimings.start();
        com.anatomist.store.IndexCompatibility.Report compatibility =
                requestedIncremental
                        ? com.anatomist.store.IndexCompatibility.inspectFast(dbPath)
                        : com.anatomist.store.IndexCompatibility.inspect(dbPath);
        if (requestedIncremental && compatibility.requiresRecreate()) {
            compatibility = com.anatomist.store.IndexCompatibility.inspect(dbPath);
        }
        phaseTimings.stop("schema_check", phaseStarted);
        if (compatibility.requiresRecreate() && !recreate) {
            System.err.println("ERROR: " + compatibility.primaryReason()
                    + ": index is incompatible with 1.0; rerun with --recreate "
                    + "(discards " + compatibility.documents() + " documents and "
                    + compatibility.semanticAnnotations() + " semantic annotations)");
            return 3;
        }

        boolean useIncremental = requestedIncremental;

        if (useIncremental) {
            try (SqliteStore store = new SqliteStore(dbPath)) {
                java.util.Map<String, FileCacheEntry> cache;
                phaseStarted = phaseTimings.start();
                try {
                    cache = store.readFileCache();
                } catch (RuntimeException ex) {
                    // Schema missing — fall back to full
                    cache = java.util.Collections.emptyMap();
                }
                phaseTimings.stop("cache_read", phaseStarted);
                boolean schemaMismatch = !cache.isEmpty() && cache.values().stream()
                        .anyMatch(e -> e.schemaVersion() != FileCacheService.CURRENT_SCHEMA_VERSION);
                if (cache.isEmpty() || schemaMismatch) {
                    String reason = cache.isEmpty()
                            ? "file_cache empty"
                            : "schema_version mismatch";
                    System.err.println("INFO: incremental degraded to full (" + reason + ")");
                    IndexRuntime runtime = resolveRuntimeTimed(cd, projectRoot, sourcePaths, phaseTimings);
                    return runFullIndex(projectRoot, sourcePaths, runtime.classpathEntries(),
                            sourceFilesForFull(scanner, resolvedSourceRoots, sourceFiles),
                            runtime.javaVersion(), runtime.factory(), dbPath, classpath, started, config, false,
                            phaseTimings, totalStarted);
                }
                if (!providerId.equals(store.readProjectMeta("provider_id").orElse(""))) {
                    System.err.println("INFO: incremental degraded to full (provider changed)");
                    IndexRuntime runtime = resolveRuntimeTimed(cd, projectRoot, sourcePaths, phaseTimings);
                    return runFullIndex(projectRoot, sourcePaths, runtime.classpathEntries(),
                            sourceFilesForFull(scanner, resolvedSourceRoots, sourceFiles),
                            runtime.javaVersion(), runtime.factory(), dbPath, classpath, started,
                            config, false, phaseTimings, totalStarted);
                }
                phaseStarted = phaseTimings.start();
                String currentExtensions = preparedExtensions(
                        projectRoot, sourcePaths).fingerprint();
                phaseTimings.stop("extension_check", phaseStarted);
                if (!currentExtensions.equals(store.readProjectMeta(
                        com.anatomist.framework.PreparedExtensions.META_KEY).orElse(""))) {
                    String reason = "extension fingerprint changed";
                    System.err.println("INFO: incremental degraded to full (" + reason + ")");
                    IndexRuntime runtime = resolveRuntimeTimed(cd, projectRoot, sourcePaths, phaseTimings);
                    return runFullIndex(projectRoot, sourcePaths, runtime.classpathEntries(),
                            sourceFilesForFull(scanner, resolvedSourceRoots, sourceFiles),
                            runtime.javaVersion(), runtime.factory(), dbPath, classpath, started, config, false,
                            phaseTimings, totalStarted);
                }
                String expectedLayoutHash = sourceLayoutHash(resolvedSourceRoots);
                if (!expectedLayoutHash.equals(store.readProjectMeta("source_layout_hash").orElse(""))) {
                    System.err.println("INFO: incremental degraded to full (source layout changed)");
                    IndexRuntime runtime = resolveRuntimeTimed(cd, projectRoot, sourcePaths, phaseTimings);
                    return runFullIndex(projectRoot, sourcePaths, runtime.classpathEntries(),
                            sourceFilesForFull(scanner, resolvedSourceRoots, sourceFiles),
                            runtime.javaVersion(), runtime.factory(), dbPath, classpath, started, config, false,
                            phaseTimings, totalStarted);
                }
                String expectedScanHash = scanPolicy.fingerprint(
                        resolvedSourceRoots, effectiveScanScopes);
                if (!expectedScanHash.equals(
                        store.readProjectMeta("scan_policy_hash").orElse(""))) {
                    String reason = "scan policy changed";
                    System.err.println("INFO: incremental degraded to full (" + reason + ")");
                    IndexRuntime runtime = resolveRuntimeTimed(cd, projectRoot, sourcePaths, phaseTimings);
                    return runFullIndex(projectRoot, sourcePaths, runtime.classpathEntries(),
                            sourceFilesForFull(scanner, resolvedSourceRoots, sourceFiles),
                            runtime.javaVersion(), runtime.factory(), dbPath, classpath, started, config, false,
                            phaseTimings, totalStarted);
                }
                int priorVersion;
                try {
                    priorVersion = Integer.parseInt(
                            store.readProjectMeta("java_version").orElse(""));
                } catch (NumberFormatException missingVersion) {
                    priorVersion = -1;
                }
                phaseStarted = phaseTimings.start();
                String currentBuildInputs = cd.classpathInputFingerprint(projectRoot);
                String priorBuildInputs = store.readProjectMeta("classpath_input_hash").orElse("");
                boolean buildInputsUnchanged = currentBuildInputs != null
                        && currentBuildInputs.equals(priorBuildInputs);
                String classpathRefreshReason = classpathRefreshReason(cd, projectRoot, store);
                phaseTimings.stop("classpath_input_scan", phaseStarted);
                if (classpathRefreshReason != null) {
                    System.err.println("INFO: incremental degraded to full (" + classpathRefreshReason + ")");
                    IndexRuntime runtime = resolveRuntimeTimed(cd, projectRoot, sourcePaths, phaseTimings);
                    return runFullIndex(projectRoot, sourcePaths, runtime.classpathEntries(),
                            sourceFilesForFull(scanner, resolvedSourceRoots, sourceFiles),
                            runtime.javaVersion(), runtime.factory(), dbPath, classpath, started, config, false,
                            phaseTimings, totalStarted);
                }
                phaseStarted = phaseTimings.start();
                com.anatomist.core.JavaVersionDetection currentVersion =
                        resolveIncrementalJavaVersion(cd, projectRoot, store,
                                priorVersion, buildInputsUnchanged);
                phaseTimings.stop("java_version_check", phaseStarted);
                boolean reusePriorExplicitVersion =
                        currentVersion.source()
                                == com.anatomist.core.JavaVersionDetection.Source.FALLBACK
                        && "cli".equalsIgnoreCase(
                                store.readProjectMeta("java_version_source").orElse(""));
                if (!reusePriorExplicitVersion && currentVersion.version() != priorVersion) {
                    String reason = "java version changed";
                    System.err.println("INFO: incremental degraded to full (" + reason + ")");
                    IndexRuntime runtime = resolveRuntimeTimed(cd, projectRoot, sourcePaths, phaseTimings);
                    return runFullIndex(projectRoot, sourcePaths, runtime.classpathEntries(),
                            sourceFilesForFull(scanner, resolvedSourceRoots, sourceFiles),
                            runtime.javaVersion(), runtime.factory(), dbPath, classpath, started, config, false,
                            phaseTimings, totalStarted);
                }
                phaseStarted = phaseTimings.start();
                FileCacheService fcs = new FileCacheService();
                java.util.Map<String, String> diskHashes;
                FileCacheService.Changes ch;
                List<Path> hashTargets = sourceFiles;
                List<Path> projectResources = new com.anatomist.framework.ProjectResourceDiscovery()
                        .discover(preparedExtensions(projectRoot, sourcePaths),
                                new com.anatomist.framework.AnalysisContext(
                                        projectRoot, sourcePaths, null, config, springXml),
                                scanner, new com.anatomist.framework.ExtensionReport())
                        .stream().map(com.anatomist.framework.ProjectResource::path).toList();
                if (!projectResources.isEmpty()) {
                    hashTargets = new ArrayList<>(sourceFiles);
                    hashTargets.addAll(projectResources);
                }
                com.anatomist.incremental.IncrementalChangeDetector.Detection detection;
                if (changedFilesFrom != null) {
                    detection = com.anatomist.incremental.IncrementalChangeDetector.fromManifest(
                            projectRoot, changedFilesFrom, hashTargets, cache, phaseTimings);
                } else if (!verifyContent) {
                    detection = com.anatomist.incremental.IncrementalChangeDetector.fromGit(
                            projectRoot, hashTargets, cache, store.readProjectMeta(), phaseTimings);
                } else {
                    detection = null;
                }
                FileCacheService.CandidateScan scan;
                String changeDetectionMode;
                int candidateFiles;
                if (detection != null) {
                    scan = detection.scan();
                    changeDetectionMode = detection.mode();
                    candidateFiles = detection.candidateFiles();
                } else {
                    long scanStarted = phaseTimings.start();
                    scan = fcs.detectChangesFast(
                            projectRoot, hashTargets, cache, verifyContent, phaseTimings);
                    phaseTimings.stop("scan_delta", scanStarted);
                    changeDetectionMode = verifyContent ? "verify-content" : "scan";
                    candidateFiles = hashTargets.size();
                }
                diskHashes = scan.diskHashes();
                ch = scan.changes();
                phaseTimings.stop("change_detection", phaseStarted);
                if (ch.isEmpty()) {
                    IncrementalIndexer.Summary summary = new IncrementalIndexer.Summary();
                    summary.changeDetectionMode = changeDetectionMode;
                    summary.candidateFiles = candidateFiles;
                    long diagnosticsStarted = phaseTimings.start();
                    summary.unresolvedSymbols = store.readResolutionDiagnosticCounts()
                            .getOrDefault("UNRESOLVED_SYMBOLS", 0L);
                    summary.droppedDanglingFacts = Integer.parseInt(store.readProjectMeta(
                            "dropped_dangling_edges").orElse("0"));
                    phaseTimings.stop("diagnostics_read", diagnosticsStarted);
                    phaseTimings.stop("total", totalStarted);
                    long elapsed = System.currentTimeMillis() - started;
                    diagnosticsStarted = phaseTimings.start();
                    com.anatomist.core.IndexHealthReport persistedHealth =
                            com.anatomist.application.IndexHealthService.read(store);
                    phaseTimings.stop("diagnostics_read", diagnosticsStarted);
                    IndexOutput.emitIncremental(format, projectRoot, dbPath, javaFileCount(cache),
                            summary, cache.size(), elapsed,
                            timings ? phaseTimings.millis() : java.util.Map.of(), persistedHealth,
                            effectiveHealthPolicy, loadedConfig.sourceName(),
                            scanPolicy.fingerprint(resolvedSourceRoots, effectiveScanScopes));
                    if (!persistedHealth.gate(effectiveHealthPolicy).passed()) return 3;
                    return 0;
                }

                com.anatomist.application.ProjectMetadata.GitSnapshotTask gitTask =
                        detection != null && !detection.gitMetadata().isEmpty()
                                ? com.anatomist.application.ProjectMetadata
                                        .completedIncrementalGitRead(detection.gitMetadata())
                                : com.anatomist.application.ProjectMetadata.startIncrementalGitRead(
                                        projectRoot, store.readProjectMeta());
                phaseStarted = phaseTimings.start();
                IndexRuntime runtime = resolveIncrementalRuntime(cd, projectRoot, sourcePaths, store);
                phaseTimings.stop("runtime", phaseStarted);

                IncrementalIndexer ii = new IncrementalIndexer(
                        projectRoot, sourcePaths, runtime.factory(), store, runtime.javaVersion(),
                        maxRealignFiles, springXml, config, resolvedSourceRoots,
                        phaseTimings);
                java.util.concurrent.atomic.AtomicReference<
                        com.anatomist.application.ProjectMetadata.WriteResult> metadataResultRef =
                        new java.util.concurrent.atomic.AtomicReference<>();
                IncrementalIndexer.Summary summary = ii.indexIncremental(
                        ch.changed, ch.added, ch.deleted, diskHashes, (target, effectiveCache) -> {
                            long metadataStarted = phaseTimings.start();
                            com.anatomist.application.ProjectMetadata.PreparedIncremental prepared =
                                    com.anatomist.application.ProjectMetadata.prepareIncremental(
                                            target, projectRoot, sourcePaths, resolvedSourceRoots,
                                            runtime.javaVersion(), runtime.classpathMode(),
                                            runtime.classpathEntries(), classpath, springXml,
                                            effectiveCache, phaseTimings, gitTask, loadedConfig,
                                            scanPolicy, effectiveScanScopes, providerId);
                            metadataResultRef.set(prepared.result());
                            java.util.Map<String, String> classpathMetadata =
                                    classpathDetectionMetadata(target, cd, projectRoot);
                            phaseTimings.stop("metadata", metadataStarted);
                            return () -> {
                                long commitStarted = phaseTimings.start();
                                com.anatomist.application.ProjectMetadata.commitIncremental(
                                        target, prepared, phaseTimings);
                                if (!classpathMetadata.isEmpty()) {
                                    target.upsertProjectMeta(classpathMetadata);
                                }
                                phaseTimings.stop("metadata_commit", commitStarted);
                            };
                        });
                summary.changeDetectionMode = changeDetectionMode;
                summary.candidateFiles = candidateFiles;

                if (summary.degradedToFull) {
                    System.err.println("INFO: incremental degraded to full ("
                            + summary.degradationReason + ")");
                    return runFullIndex(projectRoot, sourcePaths, runtime.classpathEntries(),
                            sourceFilesForFull(scanner, resolvedSourceRoots, sourceFiles),
                            runtime.javaVersion(), runtime.factory(), dbPath, classpath, started, config, false,
                            phaseTimings, totalStarted);
                }

                java.util.Map<String, FileCacheEntry> after = store.readFileCache();
                maybeAdviseGitCache(projectRoot, metadataResultRef.get());
                phaseTimings.stop("total", totalStarted);
                long variableMs = phaseTimings.millis().getOrDefault("parse_extract", 0L)
                        + phaseTimings.millis().getOrDefault("stage_write", 0L)
                        + phaseTimings.millis().getOrDefault("stage_promote", 0L)
                        + phaseTimings.millis().getOrDefault("impact_analysis", 0L);
                PerformanceHistory.recordIncremental(store, summary.reparsedFiles,
                        variableMs, phaseTimings.millis().getOrDefault("total", 0L));
                long elapsed = System.currentTimeMillis() - started;
                long diagnosticsStarted = phaseTimings.start();
                com.anatomist.core.IndexHealthReport persistedHealth =
                        com.anatomist.application.IndexHealthService.read(store);
                phaseTimings.stop("diagnostics_read", diagnosticsStarted);
                IndexOutput.emitIncremental(format, projectRoot, dbPath, javaFileCount(after),
                        summary, after.size(), elapsed,
                        timings ? phaseTimings.millis() : java.util.Map.of(), persistedHealth,
                        effectiveHealthPolicy, loadedConfig.sourceName(),
                        scanPolicy.fingerprint(resolvedSourceRoots, effectiveScanScopes));
                if (!persistedHealth.gate(effectiveHealthPolicy).passed()) return 3;
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
        com.anatomist.store.IndexCompatibility.Report previous =
                com.anatomist.store.IndexCompatibility.inspect(dbPath);
        Path temporary = dbPath.resolveSibling(dbPath.getFileName() + ".rebuild-"
                + UUID.randomUUID() + ".db");
        String rebuildReason = switch (previous.action()) {
            case CREATE -> "INITIAL_CREATE";
            case RECREATE -> previous.primaryReason();
            default -> recreateDb ? previous.primaryReason()
                    : full ? "FULL_REQUESTED" : "ENVIRONMENT_CHANGED";
        };
        IndexStateStore.write(dbPath, IndexStateStore.State.REBUILDING,
                rebuildReason, 0, temporary);
        captureFullResult = true;
        capturedFullResult = null;
        capturedFullConfig = null;
        capturedFullTimings = Map.of();
        capturedStrictParseFailure = null;
        try {
            int exit = runFullIndexDirect(projectRoot, sourcePaths, classpathEntries, sourceFiles,
                    jv, factory, temporary, classpathOverride, started, config, true,
                    phaseTimings, totalStarted);
            if (exit != 0 || capturedFullResult == null || capturedFullConfig == null) {
                IndexStateStore.write(dbPath, IndexStateStore.State.FAILED,
                        "replacement build failed: " + rebuildReason, 0, temporary);
                if (capturedFullResult != null && capturedFullConfig != null) {
                    capturedRebuild = new java.util.LinkedHashMap<>();
                    capturedRebuild.put("action", recreateDb ? "recreate" : "full");
                    capturedRebuild.put("reasons", List.of(rebuildReason));
                    capturedRebuild.put("atomic", true);
                    capturedRebuild.put("published", false);
                    capturedRebuild.put("discarded_documents", 0);
                    capturedRebuild.put("discarded_semantic_annotations", 0);
                    emitCapturedFullResult(dbPath);
                } else if (capturedStrictParseFailure != null) {
                    Map<String, Object> rejected = new java.util.LinkedHashMap<>();
                    rejected.put("action", recreateDb ? "recreate" : "full");
                    rejected.put("reasons", List.of(rebuildReason));
                    rejected.put("atomic", true);
                    rejected.put("published", false);
                    rejected.put("discarded_documents", 0);
                    rejected.put("discarded_semantic_annotations", 0);
                    IndexOutput.emitStrictParseFailure(dbPath, capturedStrictParseFailure,
                            effectiveHealthPolicy, rejected);
                }
                return exit == 0 ? 1 : exit;
            }
            com.anatomist.store.IndexCompatibility.Report built =
                    com.anatomist.store.IndexCompatibility.inspect(temporary);
            if (built.action() != com.anatomist.store.IndexCompatibility.Action.INCREMENTAL) {
                IndexStateStore.write(dbPath, IndexStateStore.State.FAILED,
                        "replacement integrity failed: " + built.primaryReason(), 0, temporary);
                System.err.println("ERROR: replacement index failed compatibility gate: "
                        + built.primaryReason());
                return 3;
            }
            try (IndexLock ignored = IndexLock.forWrite(dbPath)) {
                IndexFileSwap.promote(temporary, dbPath);
                IndexStateStore.clear(dbPath);
                capturedRebuild = new java.util.LinkedHashMap<>();
                capturedRebuild.put("action", recreateDb ? "recreate" : "full");
                capturedRebuild.put("reasons", List.of(rebuildReason));
                capturedRebuild.put("atomic", true);
                capturedRebuild.put("discarded_documents", previous.documents());
                capturedRebuild.put("discarded_semantic_annotations", previous.semanticAnnotations());
                emitCapturedFullResult(dbPath);
            }
            return 0;
        } catch (Exception failure) {
            IndexStateStore.write(dbPath, IndexStateStore.State.FAILED,
                    failure.getMessage(), 0, temporary);
            throw failure;
        } finally {
            captureFullResult = false;
            IndexStateStore.cleanupTemporary(temporary.toString());
        }
    }

    private Integer runFullIndexDirect(Path projectRoot,
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
        com.anatomist.application.IndexConfig cfg = new com.anatomist.application.IndexConfig(
                projectRoot, sourcePaths, classpathEntries, sourceFiles, providerId,
                jv, springXml, config, dbPath, classpathOverride, noClasspath, debug,
                resolveSourceRoots(projectRoot, sourcePaths),
                effectiveHealthPolicy != com.anatomist.core.HealthPolicy.NONE,
                factory == null ? null : currentJavaVersionDetection,
                loadedConfig, scanPolicy, effectiveScanScopes);
        com.anatomist.application.IndexOrchestrator orchestrator =
                new com.anatomist.application.IndexOrchestrator(cfg, factory);

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
                if (captureFullResult) store.prepareDisposableBuild();
                long fullIndexStarted = phaseTimings.start();
                com.anatomist.core.IndexResult result;
                try {
                    result = orchestrator.run(store, phaseTimings);
                } catch (com.anatomist.core.StrictHealthException rejected) {
                    phaseTimings.stop("full_index", fullIndexStarted);
                    if (captureFullResult) {
                        capturedStrictParseFailure = rejected.parseInventory();
                    } else if ("json".equalsIgnoreCase(format)) {
                        IndexOutput.emitStrictParseFailure(
                                dbPath, rejected.parseInventory(), effectiveHealthPolicy);
                    } else {
                        System.err.println("ERROR: " + rejected.getMessage());
                        rejected.parseInventory().failures().forEach((file, problems) ->
                                System.err.println("  " + file + ": "
                                        + (problems.isEmpty() ? "parse failed" : problems.get(0))));
                    }
                    return 3;
                }
                persistClasspathDetection(store, new ClasspathDetector(), projectRoot);
                phaseTimings.stop("full_index", fullIndexStarted);
                PerformanceHistory.recordFull(store,
                        phaseTimings.millis().getOrDefault("full_index", 0L),
                        sourceFiles.size());
                if (captureFullResult) store.finishDisposableBuild();
                phaseTimings.stop("total", totalStarted);
                if (captureFullResult) {
                    capturedFullResult = result;
                    capturedFullConfig = cfg;
                    capturedFullTimings = timings ? Map.copyOf(phaseTimings.millis()) : Map.of();
                } else if ("json".equalsIgnoreCase(format)) {
                    IndexOutput.emitFullJson(result, cfg,
                            timings ? phaseTimings.millis() : java.util.Map.of(),
                            effectiveHealthPolicy);
                } else {
                    com.anatomist.application.IndexStatsPrinter.print(result, cfg, System.out);
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
                if (!com.anatomist.application.IndexHealthService.fromResult(result)
                        .gate(effectiveHealthPolicy).passed()) {
                    return 3;
                }
            }
        }
        return 0;
    }

    private void emitCapturedFullResult(Path liveDb) {
        com.anatomist.application.IndexConfig cfg = withDatabase(capturedFullConfig, liveDb);
        if ("json".equalsIgnoreCase(format)) {
            IndexOutput.emitFullJson(capturedFullResult, cfg, capturedFullTimings,
                    effectiveHealthPolicy, capturedRebuild);
        } else {
            com.anatomist.application.IndexStatsPrinter.print(capturedFullResult, cfg, System.out);
            if (!capturedRebuild.isEmpty()) {
                System.out.println("  Rebuild:      " + capturedRebuild.get("action")
                        + " (" + capturedRebuild.get("reasons") + ")");
            }
            if (timings) IndexOutput.emitTimingsText(capturedFullTimings);
        }
    }

    private static com.anatomist.application.IndexConfig withDatabase(
            com.anatomist.application.IndexConfig cfg, Path database) {
        return new com.anatomist.application.IndexConfig(
                cfg.projectRoot(), cfg.sourcePaths(), cfg.classpathEntries(), cfg.sourceFiles(),
                cfg.providerId(), cfg.javaVersion(), cfg.springXml(), cfg.config(), database,
                cfg.classpathOverride(), cfg.noClasspath(), cfg.debug(), cfg.sourceRoots(),
                cfg.strictHealth(), cfg.javaVersionDetection(), cfg.loadedConfig(),
                cfg.scanPolicy(), cfg.scanScopes());
    }

    private void configureEffectiveOptions(Path projectRoot, ProjectConfig config) {
        vmClasspath = vmClasspath == null ? config.vmClasspath() : vmClasspath;
        springXml = springXml == null ? config.springXml() : springXml;
        if (lombokMode != null && !lombokMode.isBlank()) config.setLombokMode(lombokMode);
        lombokMode = config.lombokMode().optionValue();
        boolean cliRoots = !sourceRootSpecs.isEmpty()
                || (projectSource != null && !projectSource.isBlank());
        if (!cliRoots && !config.sourceRootSpecs().isEmpty()) {
            sourceRootSpecs = new ArrayList<>(config.sourceRootSpecs());
            sourceRootsFromConfig = true;
        }
        if (!sourceRootSpecs.isEmpty() && !scanScopeSpecs.isEmpty()) {
            throw new IllegalArgumentException("--scan-scope cannot be combined with explicit source roots");
        }

        effectiveScanScopes = scanScopeSpecs.isEmpty()
                ? new ArrayList<>(config.scanScopes())
                : parseScanScopes(scanScopeSpecs);
        if (includeTests && sourceRootSpecs.isEmpty()
                && (projectSource == null || projectSource.isBlank())
                && !effectiveScanScopes.contains(SourceScope.TEST)) {
            List<SourceScope> expanded = new ArrayList<>(effectiveScanScopes);
            expanded.add(SourceScope.TEST);
            effectiveScanScopes = List.copyOf(expanded);
        }
    }

    private static List<SourceScope> parseScanScopes(List<String> values) {
        java.util.LinkedHashSet<SourceScope> scopes = new java.util.LinkedHashSet<>();
        for (String value : values) {
            try {
                scopes.add(SourceScope.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)));
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException(
                        "--scan-scope must be MAIN, TEST, or GENERATED: " + value);
            }
        }
        if (scopes.isEmpty()) throw new IllegalArgumentException("--scan-scope requires a value");
        return List.copyOf(scopes);
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
        boolean willDetectClasspath = !noClasspath && (classpath == null || classpath.isEmpty());
        if (willDetectClasspath) {
            System.err.println("Detecting classpath via Maven (this can take a while)...");
        }
        List<Path> classpathEntries = resolveClasspath(cd, projectRoot);
        if (!currentClasspathDetection.diagnostics().isEmpty()) {
            java.util.List<com.anatomist.core.IndexDiagnostic> diagnostics =
                    new java.util.ArrayList<>(detected.diagnostics());
            diagnostics.addAll(currentClasspathDetection.diagnostics());
            detected = new com.anatomist.core.JavaVersionDetection(
                    detected.version(), detected.source(), detected.evidenceFile(),
                    detected.evidenceExpression(), diagnostics);
        }
        currentJavaVersionDetection = detected;
        int jv = detected.version();
        Path configuredJdkHome = validatedJdkHome(jv);
        System.err.println("Parsing with Java " + jv);
        com.anatomist.framework.PreparedExtensions extensions =
                preparedExtensions(projectRoot, sourcePaths);
        JavaParserFactory factory = new JavaParserFactory(
                jv, classpathEntries, sourcePaths, vmClasspath,
                configuredJdkHome,
                extensions.processorSuppliers());
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
            ProjectConfig loaded = loadedConfig == null
                    ? ConfigLoader.load(projectRoot) : loadedConfig.config();
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
                            + " is outside the supported analysis range "
                            + com.anatomist.core.JavaVersionDetection.supportedRange()
                            + (detected.evidenceFile() == null ? ""
                            : " (" + detected.evidenceFile() + ")"));
        }
        return detected;
    }

    private com.anatomist.core.JavaVersionDetection resolveIncrementalJavaVersion(
            ClasspathDetector detector, Path projectRoot, SqliteStore store,
            int priorVersion, boolean buildInputsUnchanged) {
        ProjectConfig config = loadedConfig == null
                ? ConfigLoader.load(projectRoot) : loadedConfig.config();
        if (javaVersion == null && !config.hasJavaVersion() && buildInputsUnchanged
                && Files.isRegularFile(projectRoot.resolve("pom.xml"))
                && priorVersion >= com.anatomist.core.JavaVersionDetection.MIN_SUPPORTED_VERSION
                && priorVersion <= com.anatomist.core.JavaVersionDetection.MAX_SUPPORTED_VERSION) {
            com.anatomist.core.JavaVersionDetection.Source source;
            try {
                source = com.anatomist.core.JavaVersionDetection.Source.valueOf(
                        store.readProjectMeta("java_version_source").orElse("maven")
                                .toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException missingSource) {
                source = com.anatomist.core.JavaVersionDetection.Source.MAVEN;
            }
            return new com.anatomist.core.JavaVersionDetection(priorVersion, source,
                    projectRoot.resolve("pom.xml"), "project_meta.java_version=" + priorVersion,
                    java.util.List.of());
        }
        return resolveJavaVersion(detector, projectRoot);
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
        if (noClasspath || (classpath != null && !classpath.isEmpty()) || javaVersion != null
                || resolveJdkHome() != null) return null;
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
        if (cachedJavaVersion < com.anatomist.core.JavaVersionDetection.MIN_SUPPORTED_VERSION
                || cachedJavaVersion > com.anatomist.core.JavaVersionDetection.MAX_SUPPORTED_VERSION) {
            throw new com.anatomist.core.JavaVersionException(3,
                    "JAVA_VERSION_UNSUPPORTED: cached Java " + cachedJavaVersion
                            + " is outside the supported analysis range "
                            + com.anatomist.core.JavaVersionDetection.supportedRange());
        }
        List<Path> cachedClasspath = parsePathList(store.readProjectMeta("classpath_entries").orElse(""));
        currentClasspathDetection = com.anatomist.core.ClasspathDetectionResult.indexMetadata(
                cachedClasspath.stream().map(Path::toString).toList());
        com.anatomist.framework.PreparedExtensions extensions =
                preparedExtensions(projectRoot, sourcePaths);
        JavaParserFactory factory = new JavaParserFactory(
                cachedJavaVersion, cachedClasspath, sourcePaths, vmClasspath,
                resolveJdkHome(),
                extensions.processorSuppliers());
        currentJavaVersionDetection = new com.anatomist.core.JavaVersionDetection(
                cachedJavaVersion, com.anatomist.core.JavaVersionDetection.Source.MAVEN,
                null, "project_meta.java_version=" + cachedJavaVersion, java.util.List.of());
        return new IndexRuntime(cachedClasspath, cachedJavaVersion, factory, "detected");
    }

    private com.anatomist.framework.PreparedExtensions preparedExtensions(
            Path projectRoot, List<Path> sourcePaths) {
        ProjectConfig config = loadedConfig == null
                ? ConfigLoader.load(projectRoot) : loadedConfig.config();
        return com.anatomist.framework.spring.BuiltInExtensions.prepare(
                new com.anatomist.framework.AnalysisContext(
                        projectRoot, sourcePaths, null, config, Boolean.TRUE.equals(springXml)));
    }

    private String classpathMode() {
        if (noClasspath) return "none";
        if (classpath != null && !classpath.isBlank()) return "explicit";
        return "detected";
    }

    private Path resolveJdkHome() {
        Path configured = jdkHome;
        if (configured == null) {
            String fromEnvironment = System.getenv(com.anatomist.core.nativeimage.LocalJdkCatalogResolver.ENV_JDK_HOME);
            if (fromEnvironment == null || fromEnvironment.isBlank()) return null;
            configured = Path.of(fromEnvironment);
        }
        return configured.toAbsolutePath().normalize();
    }

    private Path validatedJdkHome(int targetRelease) {
        Path configured = resolveJdkHome();
        if (configured == null) return null;
        try {
            int actual = com.anatomist.core.nativeimage.JdkTypeCatalogBuilder.releaseOf(configured);
            if (actual != targetRelease) {
                throw new com.anatomist.core.JavaVersionException(2,
                        "JDK_HOME_RELEASE_MISMATCH: expected Java " + targetRelease
                                + " but " + configured + " is Java " + actual);
            }
            return configured;
        } catch (com.anatomist.core.JavaVersionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new com.anatomist.core.JavaVersionException(2,
                    "JDK_HOME_INVALID: " + configured + " (" + e.getMessage() + ")");
        }
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
                                                 List<com.anatomist.core.SourceRoot> sourceRoots,
                                                 List<Path> discovered) throws java.io.IOException {
        return discovered.isEmpty() ? scanner.scanSourceRoots(sourceRoots) : discovered;
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

    private void maybeAdviseGitCache(
            Path projectRoot, com.anatomist.application.ProjectMetadata.WriteResult result) {
        if (!timings || result == null || result.gitStatusMillis() < 100) return;
        Path normalized = projectRoot.toAbsolutePath().normalize();
        if (!GIT_CACHE_ADVISED.add(normalized)) return;
        com.anatomist.application.ProjectMetadata.GitUntrackedCache state =
                com.anatomist.application.ProjectMetadata.gitUntrackedCache(normalized);
        if (state == com.anatomist.application.ProjectMetadata.GitUntrackedCache.ENABLED) return;
        System.err.println("INFO: Git untracked cache is " + state.value()
                + "; metadata_git can be faster after `git config core.untrackedCache true`");
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
        List<Path> detected = cd.detectSourcePaths(
                projectRoot, effectiveScanScopes.contains(SourceScope.TEST));
        return com.anatomist.core.SourceIdentityResolver.inferRoots(projectRoot, detected).stream()
                .filter(root -> effectiveScanScopes.contains(root.scope()))
                .map(com.anatomist.core.SourceRoot::path)
                .toList();
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
            path = path.toAbsolutePath().normalize();
            if (sourceRootsFromConfig) {
                Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
                if (!path.startsWith(normalizedRoot)) {
                    throw new ConfigException(loadedConfig.path(), 0,
                            "scan.source_roots must stay inside the project: " + spec);
                }
                if (!Files.isDirectory(path)) {
                    throw new ConfigException(loadedConfig.path(), 0,
                            "scan.source_roots directory does not exist: " + spec);
                }
            }
            roots.add(new com.anatomist.core.SourceRoot(path, module, scope));
        }
        return roots;
    }

    List<Path> resolveClasspath(ClasspathDetector cd, Path projectRoot) {
        if (noClasspath) {
            currentClasspathDetection = com.anatomist.core.ClasspathDetectionResult.notRequested();
            return Collections.emptyList();
        }
        java.util.LinkedHashSet<Path> out = new java.util.LinkedHashSet<>();
        if (classpath != null && !classpath.isEmpty()) {
            Arrays.stream(classpath.split(File.pathSeparator))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(Path::of)
                    .forEach(out::add);
            out.addAll(cd.detectBuildOutputClasspath(projectRoot));
            currentClasspathDetection = com.anatomist.core.ClasspathDetectionResult.explicit(
                    out.stream().map(Path::toString).toList());
            return new ArrayList<>(out);
        }
        currentClasspathDetection = cd.detectResult(projectRoot);
        return currentClasspathDetection.entries().stream()
                .map(Path::of).collect(Collectors.toList());
    }

    private String classpathRefreshReason(ClasspathDetector detector, Path projectRoot,
                                          SqliteStore store) {
        if (noClasspath || (classpath != null && !classpath.isBlank())) return null;
        String current = detector.classpathInputFingerprint(projectRoot);
        String previous = store.readProjectMeta("classpath_input_hash").orElse("");
        if (current == null || current.isBlank()) return null;
        if (previous.isBlank()) return "classpath inputs not recorded";
        if (current.equals(previous)) return null;

        // A POM byte-level change requires Maven to refresh the classpath cache,
        // but it does not by itself change symbol resolution. Rebuild only when
        // the refreshed artifacts differ from the committed index environment.
        List<Path> refreshedEntries = resolveClasspath(detector, projectRoot);
        String refreshedArtifacts = com.anatomist.incremental.IndexEnvironmentFingerprint
                .classpathArtifactsHash(refreshedEntries);
        String previousArtifacts = store.readProjectMeta(
                com.anatomist.incremental.IndexEnvironmentFingerprint.CLASSPATH_ARTIFACTS_KEY).orElse("");
        return refreshedArtifacts.equals(previousArtifacts) ? null : "classpath artifacts changed";
    }

    private void persistClasspathDetection(SqliteStore store, ClasspathDetector detector,
                                           Path projectRoot) {
        java.util.Map<String, String> values = classpathDetectionMetadata(store, detector, projectRoot);
        if (!values.isEmpty()) store.upsertProjectMeta(values);
    }

    private java.util.Map<String, String> classpathDetectionMetadata(
            SqliteStore store, ClasspathDetector detector, Path projectRoot) {
        if (store == null || currentClasspathDetection == null) return java.util.Map.of();
        if (currentClasspathDetection.status()
                == com.anatomist.core.ClasspathDetectionResult.Status.NOT_REQUESTED
                && store.readProjectMeta("classpath_detection_status").isPresent()) {
            return java.util.Map.of();
        }
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("classpath_detection_status", currentClasspathDetection.wireStatus());
        values.put("classpath_detection_entries",
                String.valueOf(currentClasspathDetection.entries().size()));
        values.put("classpath_detection_module_outputs",
                String.valueOf(currentClasspathDetection.moduleOutputFiles()));
        values.put("classpath_detection_build_outputs",
                String.valueOf(currentClasspathDetection.buildOutputEntries()));
        values.put("classpath_detection_maven_exit",
                currentClasspathDetection.mavenExitCode() == null
                        ? "" : String.valueOf(currentClasspathDetection.mavenExitCode()));
        values.put("classpath_detection_error_sample",
                currentClasspathDetection.errorSample() == null
                        ? "" : currentClasspathDetection.errorSample());
        String inputHash = detector.classpathInputFingerprint(projectRoot);
        if (inputHash != null && !inputHash.isBlank()) {
            values.put("classpath_input_hash", inputHash);
        }
        return java.util.Map.copyOf(values);
    }

}
