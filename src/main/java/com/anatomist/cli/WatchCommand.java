package com.anatomist.cli;

import com.anatomist.config.ConfigLoader;
import com.anatomist.config.ProjectConfig;
import com.anatomist.core.ClasspathDetector;
import com.anatomist.core.IndexEnvironmentFingerprint;
import com.anatomist.core.IndexOutcome;
import com.anatomist.core.ProjectScanner;
import com.anatomist.core.ProjectMetadata;
import com.anatomist.core.JavaParserFactory;
import com.anatomist.core.SourceIdentityResolver;
import com.anatomist.core.SourceRoot;
import com.anatomist.core.SpringBeanParser;
import com.anatomist.incremental.IncrementalParseException;
import com.anatomist.incremental.FullRebuildRequiredException;
import com.anatomist.incremental.IncrementalSessionState;
import com.anatomist.flow.FlowProfile;
import com.anatomist.store.IndexFileSwap;
import com.anatomist.store.IndexLock;
import com.anatomist.store.IndexOperationLock;
import com.anatomist.store.IndexStateStore;
import com.anatomist.store.SqliteStore;
import com.anatomist.store.WatchLease;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.stream.Collectors;

@Command(
        name = "watch",
        mixinStandardHelpOptions = true,
        description = "Watch a Java project source tree and report (or incrementally index) changes."
)
public class WatchCommand implements Callable<Integer> {

    static final int MAX_PARSE_RETRIES = 3;
    static final long MIN_PARSE_RETRY_DELAY_MS = 100L;

    @Parameters(index = "0", description = "Path to the Java project to watch.")
    Path projectPath;

    @Option(names = "--project-source",
            description = "Override project source roots (path-separator delimited).")
    String projectSource;

    @Option(names = "--source-root",
            description = "Explicit source identity: <module>@<MAIN|TEST|GENERATED>=<path>. Repeatable.")
    List<String> sourceRootSpecs = new ArrayList<>();

    @Option(names = "--include-tests",
            description = "Also watch/index src/test/java (test-only modules included). "
                    + "Off by default. Ignored when --project-source is given.")
    boolean includeTests;

    @Option(names = "--auto-index",
            description = "Trigger incremental index on change.")
    boolean autoIndex;

    @Option(names = "--extensions",
            description = "Comma-separated extensions to watch (default: .java).",
            defaultValue = ".java")
    String extensions;

    @Option(names = "--debounce-ms",
            description = "Debounce window in ms (default: 500).",
            defaultValue = "500")
    long debounceMs;

    @Option(names = "--exclude",
            description = "Comma-separated directory names to exclude.")
    String exclude;

    @Option(names = "--output",
            description = "Output SQLite database path (default: ~/.anatomist/indexes/<repo-key>/index.db).")
    Path output;

    @Option(names = "--classpath",
            description = "Override classpath entries (path-separator delimited).")
    String classpath;

    @Option(names = "--no-classpath",
            description = "Skip classpath detection.")
    boolean noClasspath;

    @Option(names = "--vm-classpath",
            description = "Add ReflectionTypeSolver so JDK types resolve.",
            defaultValue = "true", arity = "1")
    boolean vmClasspath;

    @Option(names = "--java-version",
            description = "Target Java language version (default: 8 or detected).")
    Integer javaVersion;

    @Option(names = "--max-realign-files",
            description = "Hard safety cap on the symbol-impact file set; above it, incremental degrades to full.",
            defaultValue = "1000")
    int maxRealignFiles;

    @Option(names = "--spring-xml",
            description = "Also watch + index Spring bean XML (<beans>) configs. Off by default.")
    boolean springXml;

    @Option(names = "--fail-fast",
            description = "Exit immediately when auto-index fails instead of retaining pending changes.")
    boolean failFast;

    @Option(names = "--strict-health",
            description = "Treat DEGRADED or UNHEALTHY index results as auto-index failures.")
    boolean strictHealth;

    @Option(names = "--health-policy",
            description = "Health gate: none | integrity | complete. --strict-health aliases complete.")
    String healthPolicy;

    @Option(names = "--dataflow", description = "Keep optional CFG/def-use facts current.")
    boolean dataflow;

    @Option(names = "--dataflow-mode",
            description = "Flow materialization: off | full | summary | scoped.")
    String dataflowMode;

    @Option(names = "--dataflow-scope",
            description = "Scoped flow selector. Repeatable.")
    List<String> dataflowScopes = new ArrayList<>();

    @Option(names = "--implicit-taint",
            description = "Propagate taint through control dependencies. Implies --dataflow.")
    boolean implicitTaint;

    @Option(names = "--full-policy", defaultValue = "background",
            description = "When watch needs a full rebuild: background (default), inline, or manual.")
    String fullPolicy;

    @Option(names = "--timings",
            description = "Include per-phase index timings in text/JSON output.")
    boolean timings;

    @Option(names = "--max-iterations",
            description = "Stop after N debounce cycles (for testing).",
            defaultValue = "0", hidden = true)
    int maxIterations;

    @Option(names = "--idle-timeout-ms",
            description = "Stop if no events for N ms (for testing).",
            defaultValue = "0", hidden = true)
    long idleTimeoutMs;

    private IndexCommandRunner indexCommandRunner = IndexCommand::executeOutcome;
    private Runnable readyListener = () -> {};
    private java.util.function.Consumer<IncrementalParseException> parseFailureListener = failure -> {};

    void setIndexCommandRunnerForTest(IndexCommandRunner indexCommandRunner) {
        this.indexCommandRunner = indexCommandRunner;
    }

    void setReadyListenerForTest(Runnable readyListener) {
        this.readyListener = readyListener == null ? () -> {} : readyListener;
    }

    void setParseFailureListenerForTest(
            java.util.function.Consumer<IncrementalParseException> parseFailureListener) {
        this.parseFailureListener = parseFailureListener == null ? failure -> {} : parseFailureListener;
    }

    private static final Set<String> BUILD_FILES = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

    @Override
    public Integer call() {
        try {
            com.anatomist.core.HealthPolicy.resolve(strictHealth, healthPolicy);
        } catch (IllegalArgumentException invalid) {
            System.err.println("ERROR: " + invalid.getMessage());
            return 2;
        }
        if (projectPath == null || !Files.isDirectory(projectPath)) {
            System.err.println("ERROR: project path does not exist or is not a directory: " + projectPath);
            return 1;
        }
        Path projectRoot;
        try {
            projectRoot = projectPath.toRealPath().normalize();
        } catch (java.io.IOException ex) {
            System.err.println("ERROR: unable to resolve project path: " + ex.getMessage());
            return 1;
        }
        if (projectSource != null && !projectSource.isBlank() && !sourceRootSpecs.isEmpty()) {
            System.err.println("ERROR: --project-source and --source-root are mutually exclusive");
            return 2;
        }
        ClasspathDetector cd = new ClasspathDetector();

        List<Path> sourcePaths = new ArrayList<>(resolveSourcePaths(cd, projectRoot));
        if (sourcePaths.isEmpty()) {
            System.err.println("ERROR: no source paths resolved for " + projectRoot);
            return 1;
        }

        Set<String> exts = Arrays.stream(extensions.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.startsWith(".") ? s : "." + s)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (springXml) exts.add(".xml");

        Set<String> extraExcludes = exclude == null || exclude.isEmpty()
                ? Collections.emptySet()
                : new HashSet<>(Arrays.asList(exclude.split(",")));

        Path dbPath = output == null
                ? DefaultIndexPath.forIndexWrite(projectRoot)
                : output.toAbsolutePath().normalize();
        FullPolicy configuredFullPolicy;
        try {
            configuredFullPolicy = FullPolicy.parse(fullPolicy);
        } catch (IllegalArgumentException ex) {
            System.err.println("ERROR: " + ex.getMessage());
            return 2;
        }
        WatchLease watchLease = null;
        if (autoIndex) {
            try {
                watchLease = WatchLease.acquire(dbPath);
            } catch (IndexLock.LockTimeoutException ex) {
                System.err.println("ERROR: WATCH_ALREADY_RUNNING for " + dbPath);
                return 1;
            }
        }
        IndexStateStore.Snapshot priorState = IndexStateStore.read(dbPath);
        boolean recoverInterrupted = autoIndex
                && (priorState.state() == IndexStateStore.State.REBUILDING
                || priorState.state() == IndexStateStore.State.INCREMENTAL);
        if (autoIndex) IndexStateStore.recoverInterrupted(dbPath);

        List<SourceRoot> resolvedRoots = new ArrayList<>(resolveSourceRoots(projectRoot, sourcePaths));
        Set<Path> watchedBuildFiles = discoverBuildFiles(projectRoot, sourcePaths);
        Set<Path> springXmlInventory = springXml
                ? new LinkedHashSet<>(new ProjectScanner(extraExcludes).scanSpringXml(projectRoot))
                : new LinkedHashSet<>();

        ExecutorService fullRebuildWorker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "anatomist-watch-full-rebuild");
            thread.setDaemon(true);
            return thread;
        });
        try (WatchService ws = projectRoot.getFileSystem().newWatchService();
             JavaParserFactory.SessionCache parserSessions = new JavaParserFactory.SessionCache();
             IncrementalSessionState incrementalSession = new IncrementalSessionState()) {
            ProjectMetadata.FingerprintCache fingerprintCache =
                    new ProjectMetadata.FingerprintCache();
            Map<WatchKey, Path> keys = new HashMap<>();
            // Register source dirs recursively
            for (Path sp : sourcePaths) {
                if (Files.isDirectory(sp)) registerRecursive(ws, sp, keys, extraExcludes);
            }
            // Also register project root non-recursively so we see build-file changes
            registerSingle(ws, projectRoot, keys);
            for (Path buildFile : watchedBuildFiles) {
                Path parent = buildFile.getParent();
                if (parent != null && Files.isDirectory(parent)) registerSingle(ws, parent, keys);
            }
            // Spring XML lives under resources/, outside the Java source roots — watch
            // the whole tree so <beans> config edits are seen.
            if (springXml) registerRecursive(ws, projectRoot, keys, extraExcludes);

            System.out.println("Watching " + projectRoot + " (extensions=" + exts + ", debounce=" + debounceMs + "ms"
                    + (autoIndex ? ", auto-index" : "") + ")");
            readyListener.run();

            long startedAt = System.currentTimeMillis();
            long lastEventAt = startedAt;
            // Buffered changes since the last flush.
            Map<String, String> buffered = new HashMap<>(); // relPath -> event kind
            boolean buildFileTouched = false;
            Map<String, String> pending = new HashMap<>();
            boolean pendingFullReindex = recoverInterrupted;
            boolean reconciliationRequired = recoverInterrupted;
            if (recoverInterrupted) {
                buffered.put("<interrupted-watch>", "RECONCILE");
                lastEventAt = startedAt - Math.max(debounceMs, 1L);
                System.out.println("Recovering interrupted watch rebuild in background");
            }
            boolean pendingReconciliation = false;
            boolean fastPathEnabled = true;
            int iterations = 0;
            int parseRetryCount = 0;
            long parseRetryAt = Long.MAX_VALUE;
            long lastAttemptAt = startedAt;
            long dirtyGeneration = 0;
            Future<FlushResult> backgroundFull = null;
            Map<String, String> backgroundAttempt = Map.of();
            BuildEnvironmentCheck backgroundBuildCheck = null;
            boolean backgroundCompleteEvents = false;

            while (!Thread.currentThread().isInterrupted()) {
                WatchKey k = ws.poll(100, TimeUnit.MILLISECONDS);
                long now = System.currentTimeMillis();
                if (k != null) {
                    Path dir = keys.get(k);
                    for (WatchEvent<?> ev : k.pollEvents()) {
                        WatchEvent.Kind<?> kind = ev.kind();
                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            reconciliationRequired = true;
                            buffered.put("<watch-overflow>", "OVERFLOW");
                            dirtyGeneration++;
                            lastEventAt = now;
                            continue;
                        }
                        Path name = (Path) ev.context();
                        Path full = dir == null ? name : dir.resolve(name);
                        // Recurse on newly created directory
                        if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(full)) {
                            try {
                                registerRecursive(ws, full, keys, extraExcludes);
                            } catch (IOException ignore) {}
                        }
                        if ((kind == StandardWatchEventKinds.ENTRY_CREATE
                                || kind == StandardWatchEventKinds.ENTRY_DELETE)
                                && isSourceLayoutDirectory(projectRoot, full)) {
                            reconciliationRequired = true;
                        }
                        String fname = full.getFileName().toString();
                        boolean isBuildFile = watchedBuildFiles.contains(
                                full.toAbsolutePath().normalize());
                        boolean matchesExt = exts.stream().anyMatch(fname::endsWith);
                        if (!matchesExt && !isBuildFile) continue;
                        // For build files we always care
                        if (isBuildFile) buildFileTouched = true;

                        if (springXml && fname.endsWith(".xml")) {
                            Path normalized = full.toAbsolutePath().normalize();
                            if (SpringBeanParser.isSpringBeansFile(normalized)) {
                                springXmlInventory.add(normalized);
                            } else {
                                springXmlInventory.remove(normalized);
                            }
                        }

                        String rel;
                        try {
                            rel = projectRoot.relativize(full.toAbsolutePath().normalize()).toString();
                        } catch (IllegalArgumentException ex) {
                            rel = full.toString();
                        }
                        String label = kind == StandardWatchEventKinds.ENTRY_CREATE ? "CREATE"
                                : kind == StandardWatchEventKinds.ENTRY_DELETE ? "DELETE"
                                : "MODIFY";
                        buffered.put(rel, label);
                        dirtyGeneration++;
                        lastEventAt = now;
                    }
                    boolean valid = k.reset();
                    if (!valid) keys.remove(k);
                }

                // A full rebuild runs on a dedicated worker. The watch loop must
                // keep draining WatchService while it is parsing a large project.
                FlushResult result = null;
                Map<String, String> attempt = null;
                BuildEnvironmentCheck buildCheck = null;
                boolean forceFull = false;
                boolean completeEvents = false;
                boolean resultFromBackground = false;
                if (backgroundFull != null && backgroundFull.isDone()) {
                    try {
                        result = backgroundFull.get();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return 1;
                    } catch (ExecutionException ex) {
                        System.err.println("WARN: background full rebuild failed: " + ex.getCause());
                        result = FlushResult.failed();
                    }
                    attempt = backgroundAttempt;
                    buildCheck = backgroundBuildCheck;
                    forceFull = true;
                    completeEvents = backgroundCompleteEvents;
                    resultFromBackground = true;
                    backgroundFull = null;
                }

                // Flush after debounce, or retry a transient parse failure even
                // when the filesystem does not emit another event.
                long effectiveDebounce = buildFileTouched ? Math.max(debounceMs, 1500L) : debounceMs;
                boolean eventFlushDue = !buffered.isEmpty() && now - lastEventAt >= effectiveDebounce;
                boolean parseRetryDue = buffered.isEmpty() && !pending.isEmpty()
                        && parseRetryAt != Long.MAX_VALUE && now >= parseRetryAt;
                if (result == null && backgroundFull == null && (eventFlushDue || parseRetryDue)) {
                    if (eventFlushDue) {
                        parseRetryCount = 0;
                        parseRetryAt = Long.MAX_VALUE;
                    }
                    attempt = new HashMap<>(pending);
                    attempt.putAll(buffered);
                    boolean attemptIncludesBuild = buildFileTouched
                            || containsBuildFile(projectRoot, attempt.keySet(), watchedBuildFiles);
                    buildCheck = attemptIncludesBuild
                            ? checkBuildEnvironment(cd, projectRoot, dbPath)
                            : null;
                    forceFull = pendingFullReindex
                            || (buildCheck != null && buildCheck.changed())
                            || (autoIndex && needsBootstrapFull(dbPath));
                    completeEvents = fastPathEnabled
                            && !pendingReconciliation && !reconciliationRequired;
                    if (forceFull && autoIndex && configuredFullPolicy == FullPolicy.BACKGROUND) {
                        printEvents(attempt, eventFlushDue);
                        System.out.println("Full re-index scheduled in background");
                        backgroundAttempt = Map.copyOf(attempt);
                        backgroundBuildCheck = buildCheck;
                        backgroundCompleteEvents = completeEvents;
                        Map<String, String> rebuildAttempt = backgroundAttempt;
                        long rebuildGeneration = dirtyGeneration;
                        backgroundFull = fullRebuildWorker.submit(() -> rebuildInBackground(projectRoot,
                                classpath, noClasspath, vmClasspath, javaVersion, dbPath, rebuildAttempt,
                                rebuildGeneration));
                        buffered.clear();
                        buildFileTouched = false;
                        reconciliationRequired = false;
                        lastAttemptAt = now;
                        continue;
                    }
                    if (forceFull && autoIndex && configuredFullPolicy == FullPolicy.MANUAL) {
                        IndexStateStore.write(dbPath, IndexStateStore.State.STALE,
                                "full rebuild required; run anatomist index manually", dirtyGeneration, null);
                        printEvents(attempt, eventFlushDue);
                        result = FlushResult.success();
                    } else {
                        result = flush(projectRoot, sourcePaths, classpath, noClasspath,
                                vmClasspath, javaVersion, dbPath, attempt, forceFull, autoIndex,
                                resolvedRoots, new ArrayList<>(springXmlInventory), parserSessions,
                                fingerprintCache, incrementalSession, completeEvents, eventFlushDue);
                    }
                    lastAttemptAt = now;
                }
                if (result != null) {
                    if (result.status() == FlushStatus.SUCCESS) {
                        pending.clear();
                        pendingFullReindex = false;
                        pendingReconciliation = false;
                        parseRetryCount = 0;
                        parseRetryAt = Long.MAX_VALUE;
                        if (forceFull) {
                            parserSessions.clear();
                            incrementalSession.invalidateKnownNodeIds();
                        }
                        if (buildCheck != null) {
                            sourcePaths.clear();
                            sourcePaths.addAll(buildCheck.sourcePaths());
                            resolvedRoots.clear();
                            resolvedRoots.addAll(buildCheck.sourceRoots());
                            watchedBuildFiles.clear();
                            watchedBuildFiles.addAll(discoverBuildFiles(projectRoot, sourcePaths));
                            for (Path sourcePath : sourcePaths) {
                                if (Files.isDirectory(sourcePath)) {
                                    registerRecursive(ws, sourcePath, keys, extraExcludes);
                                }
                            }
                            for (Path buildFile : watchedBuildFiles) {
                                Path parent = buildFile.getParent();
                                if (parent != null && Files.isDirectory(parent)) {
                                    registerSingle(ws, parent, keys);
                                }
                            }
                        }
                        // A successful full/reconciliation establishes a complete disk snapshot.
                        fastPathEnabled = true;
                        if (autoIndex && configuredFullPolicy != FullPolicy.MANUAL) {
                            IndexStateStore.clear(dbPath);
                        }
                    } else if (result.status() == FlushStatus.RETRYABLE_PARSE) {
                        pending.clear();
                        pending.putAll(attempt);
                        pendingFullReindex = forceFull;
                        pendingReconciliation = !completeEvents;
                        IncrementalParseException parseFailure = result.parseFailure();
                        parseFailureListener.accept(parseFailure);
                        if (failFast) {
                            System.err.println("ERROR: source parse failed (--fail-fast); "
                                    + "previous index retained: " + parseFailure.sourceFiles()
                                    + " — " + conciseDiagnostic(parseFailure));
                            return 1;
                        }
                        if (parseRetryCount < MAX_PARSE_RETRIES) {
                            parseRetryCount++;
                            long retryDelay = Math.max(MIN_PARSE_RETRY_DELAY_MS, debounceMs);
                            parseRetryAt = now + retryDelay;
                            System.err.println("WARN: source temporarily unparsable; previous index retained; "
                                    + "retry " + parseRetryCount + "/" + MAX_PARSE_RETRIES
                                    + " in " + retryDelay + "ms: " + parseFailure.sourceFiles()
                                    + " — " + conciseDiagnostic(parseFailure));
                        } else {
                            parseRetryAt = Long.MAX_VALUE;
                            System.err.println("ERROR: source remains unparsable after "
                                    + MAX_PARSE_RETRIES + " retries; previous index retained; "
                                    + "waiting for next file event: " + parseFailure.sourceFiles());
                        }
                    } else if (result.status() == FlushStatus.FULL_REQUIRED) {
                        pending.clear();
                        pending.putAll(attempt);
                        pendingFullReindex = true;
                        pendingReconciliation = !completeEvents;
                        parseRetryCount = 0;
                        parseRetryAt = now;
                        System.out.println("Full re-index required; scheduling according to --full-policy");
                    } else {
                        pending.clear();
                        pending.putAll(attempt);
                        pendingFullReindex = forceFull;
                        pendingReconciliation = !completeEvents;
                        parseRetryCount = 0;
                        parseRetryAt = Long.MAX_VALUE;
                        System.err.println("ERROR: auto-index failed; retaining "
                                + pending.size() + " pending change(s)");
                        if (failFast) return 1;
                    }
                    if (!resultFromBackground) {
                        buffered.clear();
                        buildFileTouched = false;
                        reconciliationRequired = false;
                    }
                    iterations++;
                    if (maxIterations > 0 && iterations >= maxIterations) {
                        return pending.isEmpty() ? 0 : 1;
                    }
                }

                if (idleTimeoutMs > 0 && buffered.isEmpty()
                        && parseRetryAt == Long.MAX_VALUE
                        && backgroundFull == null
                        && now - Math.max(Math.max(lastEventAt, lastAttemptAt), startedAt)
                        > idleTimeoutMs) {
                    break;
                }
            }
            if (!pending.isEmpty()) return 1;
        } catch (ClosedWatchServiceCompat | IOException e) {
            System.err.println("ERROR: watch failed: " + e.getMessage());
            return 1;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            fullRebuildWorker.shutdownNow();
            if (watchLease != null) watchLease.close();
        }
        return 0;
    }

    private FlushResult rebuildInBackground(Path projectRoot, String classpathOverride,
                                            boolean noClasspath, boolean vmClasspath,
                                            Integer javaVersion, Path liveDb,
                                            Map<String, String> dirtyFiles, long dirtyGeneration) {
        Path temporary = liveDb.resolveSibling(liveDb.getFileName() + ".rebuild-"
                + UUID.randomUUID() + ".db");
        IndexStateStore.write(liveDb, IndexStateStore.State.REBUILDING,
                "full rebuild required", dirtyGeneration, temporary);
        try (IndexOperationLock ignored = IndexOperationLock.forWrite(liveDb)) {
            System.out.println("Full re-index (background)");
            IndexCommand command = configuredFullCommand(projectRoot, classpathOverride,
                    noClasspath, vmClasspath, javaVersion, temporary, liveDb);
            FlushResult result = classifyOutcome(command, indexCommandRunner.run(command));
            if (result.status() != FlushStatus.SUCCESS) {
                IndexStateStore.write(liveDb, IndexStateStore.State.FAILED,
                        "background full index failed", dirtyGeneration, temporary);
                return result;
            }
            // Test runners may intentionally avoid materialising a SQLite file. In
            // production a successful full command always creates this target.
            if (Files.isRegularFile(temporary)) {
                try (IndexLock ignoredLive = IndexLock.forWrite(liveDb)) {
                    IndexFileSwap.promote(temporary, liveDb);
                }
            }
            IndexStateStore.clear(liveDb);
            return FlushResult.success();
        } catch (Exception ex) {
            System.err.println("WARN: background full rebuild failed: " + ex.getMessage());
            IndexStateStore.write(liveDb, IndexStateStore.State.FAILED,
                    ex.getMessage(), dirtyGeneration, temporary);
            return FlushResult.failed();
        } finally {
            IndexStateStore.cleanupTemporary(temporary.toString());
        }
    }

    private IndexCommand configuredFullCommand(Path projectRoot, String classpathOverride,
                                                boolean noClasspath, boolean vmClasspath,
                                                Integer javaVersion, Path outputPath, Path liveDb) {
        IndexCommand command = new IndexCommand();
        List<String> args = new ArrayList<>();
        args.add(projectRoot.toString());
        if (projectSource != null) { args.add("--project-source"); args.add(projectSource); }
        for (String spec : sourceRootSpecs) { args.add("--source-root"); args.add(spec); }
        if (includeTests) args.add("--include-tests");
        if (noClasspath) args.add("--no-classpath");
        if (classpathOverride != null) { args.add("--classpath"); args.add(classpathOverride); }
        args.add("--vm-classpath"); args.add(String.valueOf(vmClasspath));
        if (javaVersion != null) { args.add("--java-version"); args.add(String.valueOf(javaVersion)); }
        args.add("--output"); args.add(outputPath.toString());
        if (springXml) args.add("--spring-xml");
        appendFlowArgs(args);
        appendHealthArgs(args);
        if (timings) args.add("--timings");
        args.add("--full");
        new CommandLine(command).parseArgs(args.toArray(new String[0]));
        command.setOperationLockPath(liveDb);
        return command;
    }

    private void appendFlowArgs(List<String> args) {
        if (dataflow) args.add("--dataflow");
        if (dataflowMode != null && !dataflowMode.isBlank()) {
            args.add("--dataflow-mode");
            args.add(dataflowMode);
        }
        for (String scope : dataflowScopes) {
            args.add("--dataflow-scope");
            args.add(scope);
        }
        if (implicitTaint) args.add("--implicit-taint");
    }

    private void appendHealthArgs(List<String> args) {
        if (strictHealth) args.add("--strict-health");
        if (healthPolicy != null && !healthPolicy.isBlank()) {
            args.add("--health-policy");
            args.add(healthPolicy);
        }
    }

    private FlowProfile resolveFlowProfile(Path projectRoot) {
        ProjectConfig config = ConfigLoader.load(projectRoot);
        boolean effectiveImplicitTaint = implicitTaint || config.implicitTaint();
        List<String> scopes = dataflowScopes == null || dataflowScopes.isEmpty()
                ? config.dataflowScopes() : dataflowScopes;
        String suppliedMode = dataflowMode == null || dataflowMode.isBlank()
                ? config.dataflowMode() : dataflowMode;
        FlowProfile.Mode mode = FlowProfile.Mode.parse(suppliedMode);
        if (mode == null) {
            mode = !scopes.isEmpty() ? FlowProfile.Mode.SCOPED
                    : dataflow || config.dataflow() || effectiveImplicitTaint
                    ? FlowProfile.Mode.FULL : FlowProfile.Mode.OFF;
        }
        return new FlowProfile(mode, scopes);
    }

    private static void printEvents(Map<String, String> events, boolean print) {
        if (!print) return;
        for (Map.Entry<String, String> event : events.entrySet()) {
            System.out.println("[" + event.getValue() + "] " + event.getKey());
        }
    }

    private static boolean needsBootstrapFull(Path dbPath) {
        if (!Files.isRegularFile(dbPath)) return true;
        try (SqliteStore store = new SqliteStore(dbPath)) {
            return !store.schemaExists() || !store.schemaCompatible() || store.readFileCache().isEmpty();
        } catch (RuntimeException ex) {
            // A corrupt/unreadable DB is an operational failure, not a safe
            // schema upgrade. Preserve the existing failure-and-pending-path
            // behavior instead of silently replacing it.
            return false;
        }
    }

    private FlushResult flush(Path projectRoot, List<Path> sourcePaths, String classpathOverride,
                              boolean noClasspath, boolean vmClasspath, Integer jvOverride,
                              Path dbPath, Map<String, String> buffered, boolean buildFileTouched,
                              boolean autoIndex, List<SourceRoot> resolvedRoots,
                              List<Path> springXmlInventory,
                              JavaParserFactory.SessionCache parserSessions,
                              ProjectMetadata.FingerprintCache fingerprintCache,
                              IncrementalSessionState incrementalSession,
                              boolean completeEvents, boolean printEvents) {
        // Print events
        if (printEvents) {
            for (Map.Entry<String, String> e : buffered.entrySet()) {
                System.out.println("[" + e.getValue() + "] " + e.getKey());
            }
        }
        if (!autoIndex) return FlushResult.success();

        try {
            if (buildFileTouched) {
                // Full re-index
                incrementalSession.invalidateKnownNodeIds();
                System.out.println("Full re-index (build environment changed)");
                IndexCommand ic = new IndexCommand();
                List<String> args = new ArrayList<>();
                args.add(projectRoot.toString());
                if (projectSource != null) {
                    args.add("--project-source"); args.add(projectSource);
                }
                for (String spec : sourceRootSpecs) { args.add("--source-root"); args.add(spec); }
                if (includeTests) args.add("--include-tests");
                if (noClasspath) args.add("--no-classpath");
                if (classpathOverride != null) { args.add("--classpath"); args.add(classpathOverride); }
                args.add("--vm-classpath"); args.add(String.valueOf(vmClasspath));
                if (jvOverride != null) { args.add("--java-version"); args.add(String.valueOf(jvOverride)); }
                args.add("--output"); args.add(dbPath.toString());
                if (springXml) args.add("--spring-xml");
                appendFlowArgs(args);
                appendHealthArgs(args);
                if (timings) args.add("--timings");
                args.add("--full");
                new CommandLine(ic).parseArgs(args.toArray(new String[0]));
                return classifyOutcome(ic, indexCommandRunner.run(ic));
            }
            // Incremental
            IndexCommand ic = new IndexCommand();
            List<String> args = new ArrayList<>();
            args.add(projectRoot.toString());
            if (projectSource != null) {
                args.add("--project-source"); args.add(projectSource);
            }
            for (String spec : sourceRootSpecs) { args.add("--source-root"); args.add(spec); }
            if (includeTests) args.add("--include-tests");
            if (noClasspath) args.add("--no-classpath");
            if (classpathOverride != null) { args.add("--classpath"); args.add(classpathOverride); }
            args.add("--vm-classpath"); args.add(String.valueOf(vmClasspath));
            if (jvOverride != null) { args.add("--java-version"); args.add(String.valueOf(jvOverride)); }
            args.add("--output"); args.add(dbPath.toString());
            args.add("--incremental");
            if (springXml) args.add("--spring-xml");
            appendFlowArgs(args);
            appendHealthArgs(args);
            if (timings) args.add("--timings");
            args.add("--max-realign-files"); args.add(String.valueOf(maxRealignFiles));
            new CommandLine(ic).parseArgs(args.toArray(new String[0]));
            ic.setExecutionHints(new IndexExecutionHints(
                    resolvedRoots, buffered.keySet(), springXmlInventory, parserSessions,
                    fingerprintCache, incrementalSession, completeEvents));
            ic.deferFullFallbackForWatch();
            return classifyOutcome(ic, indexCommandRunner.run(ic));
        } catch (Exception ex) {
            System.err.println("WARN: auto-index failed: " + ex.getMessage());
            return FlushResult.failed();
        }
    }

    private static FlushResult classifyOutcome(IndexCommand command, IndexOutcome outcome) {
        if (outcome.exitCode() == 0) return FlushResult.success();
        if (outcome.cause() instanceof IncrementalParseException parseFailure) {
            return FlushResult.retryableParse(parseFailure);
        }
        if (outcome.cause() instanceof FullRebuildRequiredException) {
            return FlushResult.fullRequired();
        }
        command.reportOutcome(outcome);
        return FlushResult.failed();
    }

    private static String conciseDiagnostic(IncrementalParseException failure) {
        String singleLine = collapseAsciiRegexWhitespace(failure.firstDiagnostic()).trim();
        return singleLine.length() <= 240 ? singleLine : singleLine.substring(0, 237) + "...";
    }

    private static String collapseAsciiRegexWhitespace(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean whitespace = false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == ' ' || character == '\t' || character == '\n'
                    || character == '\u000B' || character == '\f' || character == '\r') {
                whitespace = true;
            } else {
                if (whitespace && !out.isEmpty()) out.append(' ');
                out.append(character);
                whitespace = false;
            }
        }
        return out.toString();
    }

    @FunctionalInterface
    interface IndexCommandRunner {
        IndexOutcome run(IndexCommand command);
    }

    private enum FlushStatus {
        SUCCESS,
        RETRYABLE_PARSE,
        FULL_REQUIRED,
        FAILED
    }

    private enum FullPolicy {
        BACKGROUND,
        INLINE,
        MANUAL;

        static FullPolicy parse(String raw) {
            try {
                return FullPolicy.valueOf(raw == null ? "BACKGROUND" : raw.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("--full-policy must be background, inline, or manual");
            }
        }
    }

    private record FlushResult(FlushStatus status, IncrementalParseException parseFailure) {
        static FlushResult success() {
            return new FlushResult(FlushStatus.SUCCESS, null);
        }

        static FlushResult retryableParse(IncrementalParseException failure) {
            return new FlushResult(FlushStatus.RETRYABLE_PARSE, failure);
        }

        static FlushResult fullRequired() {
            return new FlushResult(FlushStatus.FULL_REQUIRED, null);
        }

        static FlushResult failed() {
            return new FlushResult(FlushStatus.FAILED, null);
        }
    }

    private void registerRecursive(WatchService ws, Path root, Map<WatchKey, Path> keys, Set<String> excludes) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (ProjectScanner.DEFAULT_EXCLUDES.contains(name) || excludes.contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                registerSingle(ws, dir, keys);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void registerSingle(WatchService ws, Path dir, Map<WatchKey, Path> keys) throws IOException {
        Path normalized = dir.toAbsolutePath().normalize();
        if (keys.containsValue(normalized)) return;
        WatchKey key = dir.register(ws,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY);
        keys.put(key, normalized);
    }

    private Set<Path> discoverBuildFiles(Path projectRoot, List<Path> sourcePaths) {
        Set<Path> out = new LinkedHashSet<>();
        collectBuildFiles(projectRoot, out);
        for (Path sourcePath : sourcePaths) {
            Path cursor = sourcePath.toAbsolutePath().normalize();
            while (cursor != null && cursor.startsWith(projectRoot)) {
                collectBuildFiles(cursor, out);
                if (cursor.equals(projectRoot)) break;
                cursor = cursor.getParent();
            }
        }
        return out;
    }

    private static void collectBuildFiles(Path directory, Set<Path> out) {
        for (String name : BUILD_FILES) {
            Path candidate = directory.resolve(name).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate)) out.add(candidate);
        }
    }

    private static boolean containsBuildFile(Path projectRoot,
                                             Set<String> candidates,
                                             Set<Path> watchedBuildFiles) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.startsWith("<")) continue;
            Path path = Path.of(candidate);
            if (!path.isAbsolute()) path = projectRoot.resolve(path);
            if (watchedBuildFiles.contains(path.toAbsolutePath().normalize())) return true;
        }
        return false;
    }

    private BuildEnvironmentCheck checkBuildEnvironment(ClasspathDetector detector,
                                                         Path projectRoot,
                                                         Path dbPath) {
        try {
            List<Path> freshSourcePaths = resolveSourcePaths(detector, projectRoot);
            List<SourceRoot> freshRoots = resolveSourceRoots(projectRoot, freshSourcePaths);
            if (!noClasspath && (classpath == null || classpath.isBlank())) {
                System.err.println("Detecting classpath via Maven (this can take a while)...");
            }
            List<Path> classpathEntries = resolveClasspath(detector, projectRoot);
            int targetJava = javaVersion != null
                    ? javaVersion : detector.detectJavaVersion(projectRoot).orElse(8);
            String mode = noClasspath ? "none"
                    : classpath != null && !classpath.isBlank() ? "explicit" : "detected";
            IndexEnvironmentFingerprint.Snapshot current = IndexEnvironmentFingerprint.snapshot(
                    freshRoots, targetJava, mode, classpathEntries, classpath, springXml);
            Map<String, String> prior;
            if (!Files.exists(dbPath)) {
                prior = Map.of();
            } else {
                try (SqliteStore store = new SqliteStore(dbPath)) {
                    prior = store.readProjectMeta();
                }
            }
            FlowProfile flowProfile = resolveFlowProfile(projectRoot);
            boolean requestedImplicitTaint =
                    implicitTaint || ConfigLoader.load(projectRoot).implicitTaint();
            boolean flowProfileChanged =
                    !flowProfile.mode().name().toLowerCase().equals(
                            prior.getOrDefault("dataflow_mode",
                                    Boolean.parseBoolean(prior.getOrDefault("dataflow", "false"))
                                            ? "full" : "off"))
                    || !String.join(",", flowProfile.scopes()).equals(
                            prior.getOrDefault("dataflow_scopes", ""))
                    || !String.valueOf(requestedImplicitTaint).equals(
                            prior.get("implicit_taint"));
            boolean changed = !current.hash().equals(
                    prior.get(IndexEnvironmentFingerprint.META_KEY)) || flowProfileChanged;
            if (changed) {
                List<String> reasons = new ArrayList<>();
                if (!current.sourceLayoutHash().equals(prior.get("source_layout_hash"))) {
                    reasons.add("source_layout");
                }
                if (!String.valueOf(targetJava).equals(prior.get("java_version"))) {
                    reasons.add("java_version");
                }
                if (!mode.equals(prior.get("classpath_mode"))) reasons.add("classpath_mode");
                if (!current.classpathArtifactsHash().equals(
                        prior.get(IndexEnvironmentFingerprint.CLASSPATH_ARTIFACTS_KEY))) {
                    reasons.add("classpath_artifacts");
                }
                if (!String.valueOf(springXml).equals(prior.get("spring_xml"))) {
                    reasons.add("spring_xml");
                }
                if (flowProfileChanged) reasons.add("dataflow");
                if (reasons.isEmpty()) reasons.add("environment_fingerprint");
                System.out.println("Build environment changed: " + String.join(",", reasons));
            } else {
                System.out.println("Build environment unchanged; continuing incremental");
            }
            return new BuildEnvironmentCheck(changed, freshSourcePaths, freshRoots);
        } catch (Exception ex) {
            System.err.println("WARN: unable to compare build environment; using full index: "
                    + ex.getMessage());
            List<Path> fallbackPaths = new ArrayList<>(resolveSourcePaths(detector, projectRoot));
            return new BuildEnvironmentCheck(true, fallbackPaths,
                    new ArrayList<>(resolveSourceRoots(projectRoot, fallbackPaths)));
        }
    }

    private List<Path> resolveClasspath(ClasspathDetector detector, Path projectRoot) {
        if (noClasspath) return List.of();
        LinkedHashSet<Path> entries = new LinkedHashSet<>();
        if (classpath != null && !classpath.isBlank()) {
            Arrays.stream(classpath.split(File.pathSeparator))
                    .map(String::trim).filter(value -> !value.isEmpty())
                    .map(Path::of).forEach(entries::add);
            entries.addAll(detector.detectBuildOutputClasspath(projectRoot));
            return new ArrayList<>(entries);
        }
        detector.detect(projectRoot).stream().map(Path::of).forEach(entries::add);
        return new ArrayList<>(entries);
    }

    private record BuildEnvironmentCheck(boolean changed,
                                         List<Path> sourcePaths,
                                         List<SourceRoot> sourceRoots) {}

    List<Path> resolveSourcePaths(ClasspathDetector cd, Path projectRoot) {
        if (!sourceRootSpecs.isEmpty()) {
            List<Path> out = new ArrayList<>();
            for (String spec : sourceRootSpecs) {
                int eq = spec.indexOf('=');
                if (eq < 0 || eq == spec.length() - 1) continue;
                Path path = Path.of(spec.substring(eq + 1));
                out.add(path.isAbsolute() ? path : projectRoot.resolve(path));
            }
            return out;
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

    private List<SourceRoot> resolveSourceRoots(Path projectRoot, List<Path> sourcePaths) {
        if (sourceRootSpecs.isEmpty()) return SourceIdentityResolver.inferRoots(projectRoot, sourcePaths);
        List<SourceRoot> roots = new ArrayList<>();
        for (String spec : sourceRootSpecs) {
            int at = spec.indexOf('@');
            int eq = spec.indexOf('=', at + 1);
            if (at <= 0 || eq <= at + 1 || eq == spec.length() - 1) continue;
            Path path = Path.of(spec.substring(eq + 1));
            if (!path.isAbsolute()) path = projectRoot.resolve(path);
            roots.add(new SourceRoot(path,
                    spec.substring(0, at),
                    com.anatomist.core.SourceScope.valueOf(
                            spec.substring(at + 1, eq).toUpperCase(java.util.Locale.ROOT))));
        }
        return roots;
    }

    private static boolean isSourceLayoutDirectory(Path projectRoot, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(projectRoot)) return true;
        String relative = projectRoot.relativize(normalized).toString().replace('\\', '/');
        return relative.endsWith("/src/main/java") || relative.equals("src/main/java")
                || relative.endsWith("/src/test/java") || relative.equals("src/test/java")
                || relative.contains("/target/generated-sources/");
    }

    /** Tiny shim so this file compiles cleanly on any JDK regardless of throws lists. */
    private static final class ClosedWatchServiceCompat extends RuntimeException {
        ClosedWatchServiceCompat(String msg) { super(msg); }
    }
}
