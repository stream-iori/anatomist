package com.anatomist.cli;

import com.anatomist.core.ClasspathDetector;
import com.anatomist.core.ProjectScanner;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Command(
        name = "watch",
        mixinStandardHelpOptions = true,
        description = "Watch a Java project source tree and report (or incrementally index) changes."
)
public class WatchCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the Java project to watch.")
    Path projectPath;

    @Option(names = "--project-source",
            description = "Override project source roots (path-separator delimited).")
    String projectSource;

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
            description = "Output SQLite database path (default: ~/.anatomist/<repo>/index.db).")
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
            description = "Cap on the realign closure size; above it, incremental degrades to full.",
            defaultValue = "200")
    int maxRealignFiles;

    @Option(names = "--spring-xml",
            description = "Also watch + index Spring bean XML (<beans>) configs. Off by default.")
    boolean springXml;

    @Option(names = "--max-iterations",
            description = "Stop after N debounce cycles (for testing).",
            defaultValue = "0", hidden = true)
    int maxIterations;

    @Option(names = "--idle-timeout-ms",
            description = "Stop if no events for N ms (for testing).",
            defaultValue = "0", hidden = true)
    long idleTimeoutMs;

    private static final Set<String> BUILD_FILES = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

    @Override
    public Integer call() {
        if (projectPath == null || !Files.isDirectory(projectPath)) {
            System.err.println("ERROR: project path does not exist or is not a directory: " + projectPath);
            return 1;
        }
        Path projectRoot = projectPath.toAbsolutePath().normalize();
        ClasspathDetector cd = new ClasspathDetector();

        List<Path> sourcePaths = resolveSourcePaths(cd, projectRoot);
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

        try (WatchService ws = projectRoot.getFileSystem().newWatchService()) {
            Map<WatchKey, Path> keys = new HashMap<>();
            // Register source dirs recursively
            for (Path sp : sourcePaths) {
                if (Files.isDirectory(sp)) registerRecursive(ws, sp, keys, extraExcludes);
            }
            // Also register project root non-recursively so we see build-file changes
            registerSingle(ws, projectRoot, keys);
            // Spring XML lives under resources/, outside the Java source roots — watch
            // the whole tree so <beans> config edits are seen.
            if (springXml) registerRecursive(ws, projectRoot, keys, extraExcludes);

            System.out.println("Watching " + projectRoot + " (extensions=" + exts + ", debounce=" + debounceMs + "ms"
                    + (autoIndex ? ", auto-index" : "") + ")");

            long startedAt = System.currentTimeMillis();
            long lastEventAt = startedAt;
            // Buffered changes since the last flush.
            Map<String, String> buffered = new HashMap<>(); // relPath -> event kind
            boolean buildFileTouched = false;
            int iterations = 0;

            while (!Thread.currentThread().isInterrupted()) {
                WatchKey k = ws.poll(100, TimeUnit.MILLISECONDS);
                long now = System.currentTimeMillis();
                if (k != null) {
                    Path dir = keys.get(k);
                    for (WatchEvent<?> ev : k.pollEvents()) {
                        WatchEvent.Kind<?> kind = ev.kind();
                        if (kind == StandardWatchEventKinds.OVERFLOW) continue;
                        Path name = (Path) ev.context();
                        Path full = dir == null ? name : dir.resolve(name);
                        // Recurse on newly created directory
                        if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(full)) {
                            try {
                                registerRecursive(ws, full, keys, extraExcludes);
                            } catch (IOException ignore) {}
                        }
                        String fname = full.getFileName().toString();
                        boolean isBuildFile = BUILD_FILES.contains(fname);
                        boolean matchesExt = exts.stream().anyMatch(fname::endsWith);
                        if (!matchesExt && !isBuildFile) continue;
                        // For build files we always care
                        if (isBuildFile) buildFileTouched = true;

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
                        lastEventAt = now;
                    }
                    boolean valid = k.reset();
                    if (!valid) keys.remove(k);
                }

                // Flush after debounce
                if (!buffered.isEmpty() && now - lastEventAt >= debounceMs) {
                    flush(projectRoot, sourcePaths, classpath, noClasspath, vmClasspath, javaVersion,
                            dbPath, buffered, buildFileTouched, autoIndex);
                    buffered.clear();
                    buildFileTouched = false;
                    iterations++;
                    if (maxIterations > 0 && iterations >= maxIterations) break;
                }

                if (idleTimeoutMs > 0 && buffered.isEmpty()
                        && now - Math.max(lastEventAt, startedAt) > idleTimeoutMs) {
                    break;
                }
            }
        } catch (ClosedWatchServiceCompat | IOException e) {
            System.err.println("ERROR: watch failed: " + e.getMessage());
            return 1;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    private void flush(Path projectRoot, List<Path> sourcePaths, String classpathOverride,
                       boolean noClasspath, boolean vmClasspath, Integer jvOverride,
                       Path dbPath, Map<String, String> buffered, boolean buildFileTouched,
                       boolean autoIndex) {
        // Print events
        for (Map.Entry<String, String> e : buffered.entrySet()) {
            System.out.println("[" + e.getValue() + "] " + e.getKey());
        }
        if (!autoIndex) return;

        try {
            if (buildFileTouched) {
                // Full re-index
                System.out.println("Full re-index (build file changed)");
                IndexCommand ic = new IndexCommand();
                List<String> args = new ArrayList<>();
                args.add(projectRoot.toString());
                if (projectSource != null) {
                    args.add("--project-source"); args.add(projectSource);
                }
                if (noClasspath) args.add("--no-classpath");
                if (classpathOverride != null) { args.add("--classpath"); args.add(classpathOverride); }
                args.add("--vm-classpath"); args.add(String.valueOf(vmClasspath));
                if (jvOverride != null) { args.add("--java-version"); args.add(String.valueOf(jvOverride)); }
                args.add("--output"); args.add(dbPath.toString());
                if (springXml) args.add("--spring-xml");
                args.add("--full");
                new CommandLine(ic).parseArgs(args.toArray(new String[0]));
                ic.call();
                return;
            }
            // Incremental
            IndexCommand ic = new IndexCommand();
            List<String> args = new ArrayList<>();
            args.add(projectRoot.toString());
            if (projectSource != null) {
                args.add("--project-source"); args.add(projectSource);
            }
            if (noClasspath) args.add("--no-classpath");
            if (classpathOverride != null) { args.add("--classpath"); args.add(classpathOverride); }
            args.add("--vm-classpath"); args.add(String.valueOf(vmClasspath));
            if (jvOverride != null) { args.add("--java-version"); args.add(String.valueOf(jvOverride)); }
            args.add("--output"); args.add(dbPath.toString());
            args.add("--incremental");
            if (springXml) args.add("--spring-xml");
            args.add("--max-realign-files"); args.add(String.valueOf(maxRealignFiles));
            new CommandLine(ic).parseArgs(args.toArray(new String[0]));
            ic.call();
        } catch (Exception ex) {
            System.err.println("WARN: auto-index failed: " + ex.getMessage());
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
        WatchKey key = dir.register(ws,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY);
        keys.put(key, dir);
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

    /** Tiny shim so this file compiles cleanly on any JDK regardless of throws lists. */
    private static final class ClosedWatchServiceCompat extends RuntimeException {
        ClosedWatchServiceCompat(String msg) { super(msg); }
    }
}
