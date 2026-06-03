package com.anatomist.cli;

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

    @Override
    public Integer call() {
        long started = System.currentTimeMillis();
        try {
            if (projectPath == null || !Files.isDirectory(projectPath)) {
                System.err.println("ERROR: project path does not exist or is not a directory: " + projectPath);
                return 1;
            }
            Path projectRoot = projectPath.toAbsolutePath().normalize();

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
                try (SqliteStore store = new SqliteStore(dbPath)) {
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
                                jv, factory, dbPath, classpath, started);
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
                            maxRealignFiles, springXml);
                    IncrementalIndexer.Summary summary = ii.indexIncremental(
                            ch.changed, ch.added, ch.deleted, diskHashes);

                    if (summary.degradedToFull) {
                        System.err.println("INFO: incremental degraded to full ("
                                + summary.degradationReason + ")");
                        return runFullIndex(projectRoot, sourcePaths, classpathEntries, sourceFiles,
                                jv, factory, dbPath, classpath, started);
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
                    jv, factory, dbPath, classpath, started);
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
                                 long started) throws Exception {
        NodeIdGenerator idGen = new NodeIdGenerator();
        ExtractionContext ctx = new ExtractionContext(projectRoot, sourcePaths, idGen, null, "MAIN");
        TypeExtractor typeExtractor = new TypeExtractor(ctx);
        FieldExtractor fieldExtractor = new FieldExtractor(ctx);
        MethodExtractor methodExtractor = new MethodExtractor(ctx);
        AnnotationExtractor annotationExtractor = new AnnotationExtractor(ctx);
        HierarchyExtractor hierarchyExtractor = new HierarchyExtractor(ctx);
        ReferenceExtractor referenceExtractor = new ReferenceExtractor(ctx);
        CallGraphExtractor callGraphExtractor = new CallGraphExtractor(ctx);
        FieldAccessExtractor fieldAccessExtractor = new FieldAccessExtractor(ctx);

        ExtractionResult result = new ExtractionResult();

        Progress progress = new Progress(sourceFiles.size());
        factory.parseAll((filePath, cu) -> {
            String relative = filePath == null ? null : relativize(projectRoot, filePath);
            if (relative != null) cu.setData(TypeExtractor.SourceFileKey.KEY, relative);
            typeExtractor.extract(cu, result);
            fieldExtractor.extract(cu, result);
            methodExtractor.extract(cu, result);
            annotationExtractor.extract(cu, result);
            hierarchyExtractor.extract(cu, result);
            referenceExtractor.extract(cu, result);
            callGraphExtractor.extract(cu, result);
            fieldAccessExtractor.extract(cu, result);
            progress.tick();
        });
        progress.done();

        // Spring bean XML pass (opt-in). Runs after Java extraction so bean
        // class/ref FQNs can be joined against the just-extracted CLASS nodes.
        List<Path> xmlFiles = Collections.emptyList();
        if (springXml) {
            xmlFiles = new ProjectScanner().scanSpringXml(projectRoot);
            if (!xmlFiles.isEmpty()) {
                java.util.Set<String> knownIds = new HashSet<>();
                for (com.anatomist.model.Node n : result.nodes) knownIds.add(n.id);
                XmlBeanExtractor xmlExtractor = new XmlBeanExtractor("MAIN");
                SpringBeanParser beanParser = new SpringBeanParser();
                for (Path xml : xmlFiles) {
                    String rel = relativize(projectRoot, xml.toAbsolutePath().normalize());
                    xmlExtractor.extract(beanParser.parse(xml), knownIds, rel, result);
                }
            }
        }

        try (SqliteStore store = new SqliteStore(dbPath)) {
            boolean schemaExists;
            try (java.sql.Statement st = store.connection().createStatement();
                 java.sql.ResultSet rs = st.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='nodes'")) {
                schemaExists = rs.next();
            }
            if (!schemaExists) {
                store.initSchema();
            } else {
                // Wipe data, keep schema, so the file_cache & friends survive across runs.
                try (java.sql.Statement st = store.connection().createStatement()) {
                    st.execute("DELETE FROM semantic_annotations");
                    st.execute("DELETE FROM annotations");
                    st.execute("DELETE FROM edges");
                    st.execute("DELETE FROM nodes");
                    st.execute("DELETE FROM file_cache");
                    st.execute("DELETE FROM file_dependencies");
                    st.execute("DELETE FROM project_meta");
                }
            }
            int dropped = pruneDanglingInternalEdges(result);
            if (dropped > 0) {
                // Recorded, not printed to the console: dangling-internal-target
                // edges come from extractor coverage gaps (LOCAL_CLASS / nested
                // anon / LAMBDA nodes not yet emitted). Written to the per-repo
                // warn.log and persisted to project_meta so it's queryable.
                AnatomistLog.warn("dropped " + dropped + " edges with dangling internal target "
                        + "(extractor gaps) for " + projectRoot);
            }
            new SemanticPostProcessor().process(result);
            store.write(result);

            // Phase 4: populate file_cache / project_meta / file_dependencies.
            // XML files participate in the cache so incremental detects their edits.
            List<Path> cachedFiles = sourceFiles;
            if (!xmlFiles.isEmpty()) {
                cachedFiles = new ArrayList<>(sourceFiles);
                cachedFiles.addAll(xmlFiles);
            }
            populateFileCache(store, projectRoot, cachedFiles, result);
            store.upsertProjectMeta("dropped_dangling_edges", String.valueOf(dropped));
            store.upsertProjectMeta("java_version", String.valueOf(jv));
            store.upsertProjectMeta("classpath_hash",
                    FileCacheService.sha256OfString(classpathFingerprint(classpathEntries, classpathOverride)));
            store.upsertProjectMeta("index_version", String.valueOf(FileCacheService.CURRENT_SCHEMA_VERSION));
            store.clearFileDependencies();
            store.deriveFileDependencies();

            long elapsed = System.currentTimeMillis() - started;
            long types = result.nodes.stream().filter(n -> isType(n.kind)).count();
            long methods = result.nodes.stream().filter(n -> "METHOD".equals(n.kind)).count();
            long fields = result.nodes.stream().filter(n -> "FIELD".equals(n.kind)).count();
            long contains = countEdges(result, "CONTAINS");
            long inherits = countEdges(result, "INHERITS");
            long implementsRel = countEdges(result, "IMPLEMENTS");
            long overrides = countEdges(result, "OVERRIDES");
            long references = countEdges(result, "REFERENCES");
            long calls = countEdges(result, "CALLS");
            long reads = countEdges(result, "READS");
            long writes = countEdges(result, "WRITES");
            long beans = result.nodes.stream().filter(n -> "BEAN".equals(n.kind)).count();

            System.out.println("Indexed " + projectRoot);
            System.out.println("  Source paths: " + sourcePaths);
            System.out.println("  Classpath:    " + classpathEntries.size() + " jars");
            System.out.println("  Source files: " + sourceFiles.size());
            System.out.println("  Types:        " + types);
            System.out.println("  Methods:      " + methods);
            System.out.println("  Fields:       " + fields);
            System.out.println("  Annotations:  " + result.annotations.size());
            System.out.println("  CONTAINS:     " + contains);
            System.out.println("  INHERITS:     " + inherits);
            System.out.println("  IMPLEMENTS:   " + implementsRel);
            System.out.println("  OVERRIDES:    " + overrides);
            System.out.println("  REFERENCES:   " + references);
            System.out.println("  CALLS:        " + calls);
            System.out.println("  READS:        " + reads);
            System.out.println("  WRITES:       " + writes);
            if (springXml) {
                System.out.println("  Beans:        " + beans
                        + " (WIRES " + countEdges(result, "WIRES") + ")");
            }
            System.out.println("  Semantic annotations: " + result.semanticAnnotations.size());
            System.out.println("  Unresolved:   " + ctx.unresolvedCount());
            System.out.println("  File cache:   " + store.readFileCache().size() + " entries");
            System.out.println("  Output:       " + dbPath);
            if (ctx.samplingEnabled()) {
                com.anatomist.core.UnresolvedReporter.print(
                        System.out, ctx.unresolvedSamples(),
                        projectPackagesOf(result), ctx.unresolvedCount());
            }
            System.out.println("Done in " + elapsed + "ms");
        }
        return 0;
    }

    private static void populateFileCache(SqliteStore store, Path projectRoot,
                                          List<Path> sourceFiles, ExtractionResult result) {
        java.util.Map<String, int[]> perFile = new java.util.HashMap<>();
        for (com.anatomist.model.Node n : result.nodes) {
            if (n.sourceFile == null) continue;
            perFile.computeIfAbsent(n.sourceFile, k -> new int[2])[0]++;
        }
        for (com.anatomist.model.Edge e : result.edges) {
            if (e.sourceFile == null) continue;
            perFile.computeIfAbsent(e.sourceFile, k -> new int[2])[1]++;
        }
        String now = java.time.Instant.now().toString();
        java.util.List<FileCacheEntry> entries = new java.util.ArrayList<>();
        for (Path f : sourceFiles) {
            String rel;
            try {
                rel = projectRoot.relativize(f.toAbsolutePath().normalize()).toString();
            } catch (IllegalArgumentException ex) {
                rel = f.toAbsolutePath().toString();
            }
            String hash = FileCacheService.sha256(f.toAbsolutePath().normalize());
            int[] cnt = perFile.getOrDefault(rel, new int[]{0, 0});
            entries.add(new FileCacheEntry(rel, hash, FileCacheService.CURRENT_SCHEMA_VERSION,
                    now, cnt[0], cnt[1]));
        }
        if (!entries.isEmpty()) store.updateFileCache(entries);
    }

    private static String classpathFingerprint(List<Path> classpathEntries, String override) {
        if (override != null && !override.isEmpty()) return override;
        if (classpathEntries == null || classpathEntries.isEmpty()) return "";
        java.util.List<String> sorted = classpathEntries.stream()
                .map(Path::toString).sorted().collect(java.util.stream.Collectors.toList());
        return String.join(File.pathSeparator, sorted);
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

    private static String relativize(Path root, Path file) {
        try {
            return root.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    private static boolean isType(String kind) {
        return "CLASS".equals(kind) || "INTERFACE".equals(kind) || "ENUM".equals(kind)
                || "ANONYMOUS_CLASS".equals(kind) || "RECORD".equals(kind);
    }

    private static long countEdges(ExtractionResult r, String relation) {
        return r.edges.stream().filter(e -> relation.equals(e.relation)).count();
    }

    /** Package names of every project-internal type node, for unresolved bucketing. */
    private static java.util.Set<String> projectPackagesOf(ExtractionResult result) {
        java.util.Set<String> projectPackages = new java.util.HashSet<>();
        for (com.anatomist.model.Node n : result.nodes) {
            if (!isType(n.kind) || n.qualifiedName == null) continue;
            int dot = n.qualifiedName.lastIndexOf('.');
            if (dot > 0) projectPackages.add(n.qualifiedName.substring(0, dot));
        }
        return projectPackages;
    }

    /**
     * Drop any edge / annotation whose source / internal target does not
     * resolve to a known node — would otherwise blow the FK on insert. The
     * underlying coverage gap (LOCAL_CLASS / LAMBDA / METHOD_REF nodes not
     * yet emitted) is the actual fix; this is the last line of defence.
     */
    private static int pruneDanglingInternalEdges(ExtractionResult r) {
        java.util.Set<String> known = new java.util.HashSet<>();
        for (com.anatomist.model.Node n : r.nodes) known.add(n.id);
        int before = r.edges.size() + r.annotations.size();
        boolean dbg = com.anatomist.core.logging.AnatomistLog.isDebugEnabled();
        r.edges.removeIf(e -> {
            boolean drop = !e.isExternal && (e.targetId == null || !known.contains(e.targetId));
            if (drop && dbg) com.anatomist.core.logging.AnatomistLog.debug(
                    "pruned edge (dangling target): " + e.relation + " "
                            + e.sourceId + " -> " + e.targetId);
            return drop;
        });
        r.edges.removeIf(e -> {
            boolean drop = e.sourceId == null || !known.contains(e.sourceId);
            if (drop && dbg) com.anatomist.core.logging.AnatomistLog.debug(
                    "pruned edge (dangling source): " + e.relation + " "
                            + e.sourceId + " -> " + e.targetId);
            return drop;
        });
        r.annotations.removeIf(a -> {
            boolean drop = a.nodeId == null || !known.contains(a.nodeId);
            if (drop && dbg) com.anatomist.core.logging.AnatomistLog.debug(
                    "pruned annotation (dangling node): " + a.annotationFqn
                            + " on " + a.nodeId);
            return drop;
        });
        return before - r.edges.size() - r.annotations.size();
    }

    /**
     * Per-file progress for the parse+extract loop, where lazy symbol
     * resolution dominates wall time. Live {@code \r} updates when stderr is a
     * terminal; periodic newline-terminated lines when piped/redirected so the
     * output stays readable in logs. {@code System.console()} is native-image
     * safe and returns null when stderr isn't a TTY.
     */
    private static final class Progress {
        private final int total;
        private final boolean tty;
        private int done;
        private long lastEmit;

        Progress(int total) {
            this.total = total;
            this.tty = System.console() != null;
        }

        void tick() {
            done++;
            if (tty) {
                long now = System.currentTimeMillis();
                if (now - lastEmit >= 100) {
                    System.err.print("\rParsing & extracting: " + done + "/" + total + " files");
                    System.err.flush();
                    lastEmit = now;
                }
            } else if (done % 200 == 0) {
                System.err.println("Parsing & extracting: " + done + "/" + total + " files");
            }
        }

        void done() {
            if (tty) {
                System.err.print("\rParsing & extracting: " + done + "/" + total + " files\n");
                System.err.flush();
            } else {
                System.err.println("Parsing & extracting: " + done + "/" + total + " files (done)");
            }
        }
    }
}
