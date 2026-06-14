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
import com.anatomist.incremental.IncrementalIndexer;
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
        description = "Index a Java project into a SQLite database."
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

            boolean useIncremental = incremental && !full && Files.exists(dbPath);

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
                                jv, factory, dbPath, classpath, started, config);
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
                                jv, factory, dbPath, classpath, started, config);
                    }

                    long elapsed = System.currentTimeMillis() - started;
                    System.out.println("Indexed " + projectRoot + " (incremental)");
                    System.out.println("  Changed files: " + summary.changedFiles);
                    System.out.println("  New files:     " + summary.newFiles);
                    System.out.println("  Deleted files: " + summary.deletedFiles);
                    System.out.println("  Realigned deps:" + summary.realignedDependents);
                    System.out.println("  New nodes:     " + summary.newNodes);
                    System.out.println("  New edges:     " + summary.newEdges);
                    System.out.println("  Output:        " + dbPath);
                    java.util.Map<String, FileCacheEntry> after = store.readFileCache();
                    System.out.println("  File cache:    " + after.size() + " entries");
                    System.out.println("Done in " + elapsed + "ms");
                    return 0;
                }
            }

            return runFullIndex(projectRoot, sourcePaths, classpathEntries, sourceFiles,
                    jv, factory, dbPath, classpath, started, config);
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
                                 ProjectConfig config) throws Exception {
        com.anatomist.core.IndexConfig cfg = new com.anatomist.core.IndexConfig(
                projectRoot, sourcePaths, classpathEntries, sourceFiles,
                jv, springXml, config, dbPath, classpathOverride, debug);
        com.anatomist.core.IndexOrchestrator orchestrator =
                new com.anatomist.core.IndexOrchestrator(cfg, factory);

        try (com.anatomist.store.IndexLock wLock = com.anatomist.store.IndexLock.forWrite(dbPath);
             SqliteStore store = new SqliteStore(dbPath)) {
            com.anatomist.core.IndexResult result = orchestrator.run(store);
            com.anatomist.core.IndexStatsPrinter.print(result, cfg, System.out);
            if (result.samplingEnabled() && result.unresolvedSamples() != null) {
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
        return 0;
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
        if (classpath != null && !classpath.isEmpty()) {
            return Arrays.stream(classpath.split(File.pathSeparator))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(Path::of)
                    .collect(Collectors.toList());
        }
        return cd.detect(projectRoot).stream().map(Path::of).collect(Collectors.toList());
    }

}
