package com.anatomist.application;

import com.anatomist.config.LoadedConfig;
import com.anatomist.core.*;
import com.anatomist.incremental.IndexEnvironmentFingerprint;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.store.FileCacheService;
import com.anatomist.store.SqliteStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ProjectMetadata {

    public static final String SNAPSHOT_FINGERPRINT_KEY = "source_snapshot_fingerprint";

    private ProjectMetadata() {}

    static void write(SqliteStore store, IndexConfig cfg, int dropped, int rebound, int wired) {
        write(store, cfg, dropped, rebound, wired, null);
    }

    static void write(SqliteStore store, IndexConfig cfg, int dropped, int rebound, int wired,
                      IndexTimings timings) {
        Map<String, FileCacheEntry> fileCache = store.readFileCache();
        long phaseStarted = System.nanoTime();
        String fingerprint = sourceSnapshotFingerprint(
                cfg.projectRoot(), cfg.sourceRoots(), fileCache);
        addTiming(timings, "metadata_fingerprint", phaseStarted);
        Map<String, String> values = baseMetadata(
                cfg.projectRoot(), cfg.sourcePaths(), cfg.sourceRoots(), cfg.javaVersion(),
                classpathMode(cfg), cfg.classpathEntries(), cfg.classpathOverride(),
                cfg.springXml(), fingerprint, cfg.loadedConfig(), cfg.scanPolicy(), cfg.scanScopes());
        phaseStarted = System.nanoTime();
        addGit(values, GitSnapshot.read(cfg.projectRoot()));
        addTiming(timings, "metadata_git", phaseStarted);
        values.put("dropped_dangling_edges", String.valueOf(dropped));
        values.put("rebound_external_edges", String.valueOf(rebound));
        values.put("wiring_resolved_edges", String.valueOf(wired));
        values.put("dataflow", String.valueOf(cfg.dataflow()));
        values.put("dataflow_mode", cfg.flowProfile().mode().name().toLowerCase());
        values.put("dataflow_scopes", String.join(",", cfg.flowProfile().scopes()));
        values.put("implicit_taint", String.valueOf(cfg.implicitTaint()));
        if (cfg.javaVersionDetection() != null) {
            values.put("java_version_source",
                    cfg.javaVersionDetection().source().name().toLowerCase());
        }
        phaseStarted = System.nanoTime();
        store.upsertProjectMeta(values);
        addTiming(timings, "metadata_write", phaseStarted);
    }

    public static WriteResult writeIncremental(SqliteStore store,
                                               Path projectRoot,
                                               List<Path> sourcePaths,
                                               List<SourceRoot> sourceRoots,
                                               int javaVersion,
                                               String classpathMode,
                                               List<Path> classpathEntries,
                                               String classpathOverride,
                                               boolean springXml,
                                               Map<String, FileCacheEntry> fileCache,
                                               IndexTimings timings) {
        return writeIncremental(store, projectRoot, sourcePaths, sourceRoots, javaVersion,
                classpathMode, classpathEntries, classpathOverride, springXml, fileCache,
                timings, null, null, null, List.of());
    }

    public static WriteResult writeIncremental(SqliteStore store,
                                               Path projectRoot,
                                               List<Path> sourcePaths,
                                               List<SourceRoot> sourceRoots,
                                               int javaVersion,
                                               String classpathMode,
                                               List<Path> classpathEntries,
                                               String classpathOverride,
                                               boolean springXml,
                                               Map<String, FileCacheEntry> fileCache,
                                               IndexTimings timings,
                                               GitSnapshotTask gitTask) {
        return writeIncremental(store, projectRoot, sourcePaths, sourceRoots, javaVersion,
                classpathMode, classpathEntries, classpathOverride, springXml, fileCache,
                timings, gitTask, null, null, List.of());
    }

    public static WriteResult writeIncremental(SqliteStore store,
                                               Path projectRoot,
                                               List<Path> sourcePaths,
                                               List<SourceRoot> sourceRoots,
                                               int javaVersion,
                                               String classpathMode,
                                               List<Path> classpathEntries,
                                               String classpathOverride,
                                               boolean springXml,
                                               Map<String, FileCacheEntry> fileCache,
                                               IndexTimings timings,
                                               GitSnapshotTask gitTask,
                                               LoadedConfig loadedConfig,
                                               ScanPolicy scanPolicy,
                                               List<SourceScope> scanScopes) {
        Map<String, FileCacheEntry> effectiveCache = fileCache == null
                ? store.readFileCache()
                : fileCache;

        long phaseStarted = System.nanoTime();
        String fingerprint = sourceSnapshotFingerprint(projectRoot, sourceRoots, effectiveCache);
        addTiming(timings, "metadata_fingerprint", phaseStarted);

        Map<String, String> prior = store.readProjectMeta();
        phaseStarted = System.nanoTime();
        GitRead git = gitTask == null
                ? GitSnapshot.readIncremental(projectRoot, prior)
                : gitTask.await();
        addTiming(timings, "git_status_wait", phaseStarted);
        if (git != null && timings != null) timings.addNanos("metadata_git", git.statusNanos());

        Map<String, String> values = baseMetadata(
                projectRoot, sourcePaths, sourceRoots, javaVersion, classpathMode,
                classpathEntries, classpathOverride, springXml, fingerprint,
                loadedConfig, scanPolicy, scanScopes);
        if (git != null) addGit(values, git.snapshot());

        phaseStarted = System.nanoTime();
        store.upsertProjectMeta(values);
        addTiming(timings, "metadata_write", phaseStarted);
        return new WriteResult(git == null ? 0L : git.statusNanos() / 1_000_000L);
    }

    public static GitSnapshotTask startIncrementalGitRead(Path projectRoot,
                                                           Map<String, String> prior) {
        return new GitSnapshotTask(CompletableFuture.supplyAsync(
                () -> GitSnapshot.readIncremental(projectRoot, prior)));
    }

    public static final class GitSnapshotTask {
        private final CompletableFuture<GitRead> future;

        private GitSnapshotTask(CompletableFuture<GitRead> future) {
            this.future = future;
        }

        private GitRead await() {
            try {
                return future.get(2200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException | TimeoutException e) {
                future.cancel(true);
                return null;
            }
        }
    }

    private static Map<String, String> baseMetadata(Path projectRoot,
                                                    List<Path> sourcePaths,
                                                    List<SourceRoot> sourceRoots,
                                                    int javaVersion,
                                                    String classpathMode,
                                                    List<Path> classpathEntries,
                                                    String classpathOverride,
                                                    boolean springXml,
                                                    String fingerprint,
                                                    LoadedConfig loadedConfig,
                                                    ScanPolicy scanPolicy,
                                                    List<SourceScope> scanScopes) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("source_root", projectRoot.toAbsolutePath().normalize().toString());
        values.put("source_paths", joinPaths(sourcePaths));
        String sourceLayout = sourceLayout(sourceRoots);
        values.put("source_layout", sourceLayout);
        values.put("source_layout_hash", FileCacheService.sha256OfString(sourceLayout));
        values.put("indexed_at", Instant.now().toString());
        values.put("java_version", String.valueOf(javaVersion));
        values.put("classpath_mode", classpathMode);
        values.put("classpath_entries", joinPaths(classpathEntries));
        values.put("classpath_override", classpathOverride == null ? "" : classpathOverride);
        values.put("classpath_hash", FileCacheService.sha256OfString(
                IndexOrchestrator.classpathFingerprint(classpathEntries, classpathOverride)));
        values.put("spring_xml", String.valueOf(springXml));
        values.put("index_version", String.valueOf(FileCacheService.CURRENT_SCHEMA_VERSION));
        values.put(GraphSemantics.META_KEY, String.valueOf(GraphSemantics.VERSION));
        List<SourceScope> effectiveScopes = scanScopes == null ? List.of() : scanScopes;
        String scanCanonical = scanPolicy == null ? "" : scanPolicy.canonical(sourceRoots, effectiveScopes);
        String scanHash = scanPolicy == null ? "" : scanPolicy.fingerprint(sourceRoots, effectiveScopes);
        values.put("config_source", loadedConfig == null ? "default" : loadedConfig.sourceName());
        values.put("config_path", loadedConfig == null || loadedConfig.path() == null
                ? "" : loadedConfig.path().toString());
        values.put("scan_policy", scanCanonical);
        values.put("scan_policy_hash", scanHash);
        IndexEnvironmentFingerprint.Snapshot environment = IndexEnvironmentFingerprint.snapshot(
                sourceRoots, javaVersion, classpathMode, classpathEntries,
                classpathOverride, springXml, false, false, scanHash);
        values.put(IndexEnvironmentFingerprint.META_KEY, environment.hash());
        values.put(IndexEnvironmentFingerprint.CLASSPATH_ARTIFACTS_KEY,
                environment.classpathArtifactsHash());
        values.put(SNAPSHOT_FINGERPRINT_KEY, fingerprint);
        return values;
    }

    private static void addGit(Map<String, String> values, GitSnapshot git) {
        if (git == null) return;
        values.put("source_git_root", git.root());
        values.put("source_git_commit", git.commit());
        values.put("source_git_branch", git.branch());
        values.put("source_git_dirty", String.valueOf(git.dirty()));
        values.put("source_git_commit_time", git.commitTime());
        values.put("source_git_remote_origin_url", git.remoteOriginUrl());
    }

    private static void addTiming(IndexTimings timings, String phase, long started) {
        if (timings != null) timings.addNanos(phase, System.nanoTime() - started);
    }

    /**
     * Portable source identity: only logical source identity and content hashes enter the digest.
     * Machine paths, timestamps, Git checkout location, and SQLite bytes are deliberately excluded.
     */
    public static String sourceSnapshotFingerprint(Path projectRoot,
                                                   List<SourceRoot> sourceRoots,
                                                   Map<String, FileCacheEntry> cache) {
        Path root = projectRoot.toAbsolutePath().normalize();
        List<SourceRoot> roots = normalizedRoots(sourceRoots);
        StringBuilder canonical = new StringBuilder("anatomist-source-snapshot-v1\n");
        if (cache != null) {
            cache.values().stream()
                    .map(entry -> portableFileIdentity(
                            root, roots, entry.sourceFile()) + "\u0000" + entry.hash())
                    .sorted()
                    .forEach(line -> canonical.append("file\u0000").append(line).append('\n'));
        }
        return "sha256:" + FileCacheService.sha256OfString(canonical.toString());
    }

    private static List<SourceRoot> normalizedRoots(List<SourceRoot> sourceRoots) {
        if (sourceRoots == null) return List.of();
        return sourceRoots.stream()
                .map(root -> new SourceRoot(root.path().toAbsolutePath().normalize(),
                        root.module(), root.scope()))
                .sorted(Comparator.comparingInt((SourceRoot r) -> r.path().getNameCount()).reversed())
                .toList();
    }

    private static String portableFileIdentity(Path projectRoot,
                                               List<SourceRoot> roots,
                                               String sourceFile) {
        Path file = Path.of(sourceFile);
        if (!file.isAbsolute()) file = projectRoot.resolve(file);
        Path normalized = file.toAbsolutePath().normalize();
        for (SourceRoot root : roots) {
            if (normalized.startsWith(root.path())) {
                String relative = root.path().relativize(normalized).toString().replace('\\', '/');
                return root.module() + "@" + root.scope().name() + ":" + relative;
            }
        }
        String relative;
        try {
            relative = projectRoot.relativize(normalized).toString().replace('\\', '/');
        } catch (IllegalArgumentException ex) {
            relative = normalized.getFileName() == null ? "external" : normalized.getFileName().toString();
        }
        return "project@MAIN:" + relative;
    }

    private static String classpathMode(IndexConfig cfg) {
        if (cfg.noClasspath()) return "none";
        if (cfg.classpathOverride() != null && !cfg.classpathOverride().isBlank()) return "explicit";
        return "detected";
    }

    private static String joinPaths(List<Path> paths) {
        if (paths == null || paths.isEmpty()) return "";
        return String.join(java.io.File.pathSeparator, paths.stream()
                .map(p -> p.toAbsolutePath().normalize().toString())
                .toList());
    }

    private static String sourceLayout(List<SourceRoot> sourceRoots) {
        if (sourceRoots == null || sourceRoots.isEmpty()) return "";
        return sourceRoots.stream()
                .map(r -> r.module() + "@" + r.scope() + "="
                        + r.path().toAbsolutePath().normalize())
                .sorted().collect(java.util.stream.Collectors.joining("\n"));
    }

    public static GitUntrackedCache gitUntrackedCache(Path projectRoot) {
        String configured = GitSnapshot.git(projectRoot,
                "config", "--bool", "--get", "core.untrackedCache");
        if ("true".equalsIgnoreCase(configured)) return GitUntrackedCache.ENABLED;
        if ("false".equalsIgnoreCase(configured)) return GitUntrackedCache.DISABLED;
        return GitUntrackedCache.UNKNOWN;
    }

    /** Best-effort current checkout identity for read-only diagnostics. */
    public static String currentGitCommit(Path projectRoot) {
        return GitSnapshot.git(projectRoot, "rev-parse", "HEAD");
    }

    public enum GitUntrackedCache {
        ENABLED, DISABLED, UNKNOWN;

        public String value() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public record WriteResult(long gitStatusMillis) {}

    private record GitRead(GitSnapshot snapshot, long statusNanos) {}

    private record GitSnapshot(String root, String commit, String branch, boolean dirty,
                               String commitTime, String remoteOriginUrl) {
        static GitSnapshot read(Path projectRoot) {
            String root = git(projectRoot, "rev-parse", "--show-toplevel");
            String commit = git(projectRoot, "rev-parse", "HEAD");
            if (root == null || commit == null) return null;
            String branch = git(projectRoot, "rev-parse", "--abbrev-ref", "HEAD");
            String status = git(projectRoot, "status", "--porcelain");
            String commitTime = git(projectRoot, "show", "-s", "--format=%cI", "HEAD");
            String remote = git(projectRoot, "config", "--get", "remote.origin.url");
            return new GitSnapshot(root, commit, branch,
                    status != null && !status.isBlank(), commitTime, remote);
        }

        static GitRead readIncremental(Path projectRoot, Map<String, String> prior) {
            long statusStarted = System.nanoTime();
            String status = git(projectRoot, "status", "--porcelain=v2", "--branch");
            long statusNanos = System.nanoTime() - statusStarted;
            if (status == null) return new GitRead(null, statusNanos);

            String commit = null;
            String branch = null;
            boolean dirty = false;
            for (String line : status.split("\\R")) {
                if (line.startsWith("# branch.oid ")) {
                    commit = line.substring("# branch.oid ".length()).trim();
                    if ("(initial)".equals(commit)) commit = null;
                } else if (line.startsWith("# branch.head ")) {
                    branch = line.substring("# branch.head ".length()).trim();
                    if ("(detached)".equals(branch)) branch = "HEAD";
                } else if (!line.isBlank() && !line.startsWith("#")) {
                    dirty = true;
                }
            }
            if (commit == null) return new GitRead(null, statusNanos);

            String root = prior.get("source_git_root");
            if (root == null || root.isBlank()) {
                root = git(projectRoot, "rev-parse", "--show-toplevel");
            }
            if (root == null) return new GitRead(null, statusNanos);

            String commitTime = prior.get("source_git_commit_time");
            if (!commit.equals(prior.get("source_git_commit"))
                    || commitTime == null || commitTime.isBlank()) {
                commitTime = git(projectRoot, "show", "-s", "--format=%cI", commit);
            }
            String remote = git(projectRoot, "config", "--get", "remote.origin.url");
            if (branch == null || branch.isBlank()) branch = prior.get("source_git_branch");
            return new GitRead(new GitSnapshot(
                    root, commit, branch, dirty, commitTime, remote), statusNanos);
        }

        private static String git(Path cwd, String... args) {
            try {
                java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
                cmd.add("git");
                cmd.addAll(List.of(args));
                Process p = new ProcessBuilder(cmd)
                        .directory(cwd.toFile())
                        .redirectErrorStream(true)
                        .start();
                boolean done = p.waitFor(2, TimeUnit.SECONDS);
                if (!done) {
                    p.destroyForcibly();
                    return null;
                }
                String out = new String(
                        p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                return p.exitValue() == 0 ? out : null;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return null;
            }
        }
    }
}
