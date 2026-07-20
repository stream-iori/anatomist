package com.anatomist.core;

import com.anatomist.model.FileCacheEntry;
import com.anatomist.store.FileCacheService;
import com.anatomist.store.SqliteStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
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
                cfg.springXml(), fingerprint);
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
                                               FingerprintCache fingerprintCache,
                                               IndexTimings timings) {
        return writeIncremental(store, projectRoot, sourcePaths, sourceRoots, javaVersion,
                classpathMode, classpathEntries, classpathOverride, springXml, fileCache,
                fingerprintCache, timings, null);
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
                                               FingerprintCache fingerprintCache,
                                               IndexTimings timings,
                                               GitSnapshotTask gitTask) {
        Map<String, FileCacheEntry> effectiveCache = fileCache == null
                ? store.readFileCache()
                : fileCache;

        long phaseStarted = System.nanoTime();
        String fingerprint = sourceSnapshotFingerprint(
                projectRoot, sourceRoots, effectiveCache, fingerprintCache);
        addTiming(timings, "metadata_fingerprint", phaseStarted);

        Map<String, String> prior = store.readProjectMeta();
        phaseStarted = System.nanoTime();
        GitRead git = gitTask == null
                ? GitSnapshot.readIncremental(projectRoot, prior, fingerprintCache)
                : gitTask.await();
        addTiming(timings, "git_status_wait", phaseStarted);
        if (git != null && timings != null) timings.addNanos("metadata_git", git.statusNanos());

        Map<String, String> values = baseMetadata(
                projectRoot, sourcePaths, sourceRoots, javaVersion, classpathMode,
                classpathEntries, classpathOverride, springXml, fingerprint);
        if (git != null) addGit(values, git.snapshot());

        phaseStarted = System.nanoTime();
        store.upsertProjectMeta(values);
        addTiming(timings, "metadata_write", phaseStarted);
        return new WriteResult(git == null ? 0L : git.statusNanos() / 1_000_000L);
    }

    public static GitSnapshotTask startIncrementalGitRead(Path projectRoot,
                                                           Map<String, String> prior,
                                                           FingerprintCache cache) {
        return new GitSnapshotTask(CompletableFuture.supplyAsync(
                () -> GitSnapshot.readIncremental(projectRoot, prior, cache)));
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
                                                    String fingerprint) {
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
        IndexEnvironmentFingerprint.Snapshot environment = IndexEnvironmentFingerprint.snapshot(
                sourceRoots, javaVersion, classpathMode, classpathEntries,
                classpathOverride, springXml, false, false);
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
        return sourceSnapshotFingerprint(projectRoot, sourceRoots, cache, null);
    }

    private static String sourceSnapshotFingerprint(Path projectRoot,
                                                    List<SourceRoot> sourceRoots,
                                                    Map<String, FileCacheEntry> cache,
                                                    FingerprintCache identityCache) {
        Path root = projectRoot.toAbsolutePath().normalize();
        List<SourceRoot> roots = normalizedRoots(sourceRoots);
        if (identityCache != null) identityCache.prepare(root, roots);
        StringBuilder canonical = new StringBuilder("anatomist-source-snapshot-v1\n");
        if (cache != null) {
            cache.values().stream()
                    .map(entry -> portableFileIdentity(
                            root, roots, entry.sourceFile(), identityCache) + "\u0000" + entry.hash())
                    .sorted()
                    .forEach(line -> canonical.append("file\u0000").append(line).append('\n'));
            if (identityCache != null) identityCache.retain(cache);
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
                                               String sourceFile,
                                               FingerprintCache identityCache) {
        if (identityCache != null) {
            return identityCache.identity(sourceFile,
                    () -> portableFileIdentity(projectRoot, roots, sourceFile, null));
        }
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

    /** Watch-owned cache of stable source-file to portable-identity mappings. */
    public static final class FingerprintCache {
        private String signature;
        private boolean splitGitStatus;
        private final Map<String, String> identities = new HashMap<>();

        private synchronized void prepare(Path projectRoot, List<SourceRoot> roots) {
            String next = projectRoot + "\n" + sourceLayout(roots);
            if (!next.equals(signature)) {
                identities.clear();
                splitGitStatus = false;
                signature = next;
            }
        }

        private synchronized String identity(String sourceFile,
                                             java.util.function.Supplier<String> resolver) {
            return identities.computeIfAbsent(sourceFile, ignored -> resolver.get());
        }

        private synchronized void retain(Map<String, FileCacheEntry> cache) {
            identities.keySet().retainAll(cache.keySet());
        }

        private synchronized boolean splitGitStatus() {
            return splitGitStatus;
        }

        private synchronized void preferSplitGitStatus(long elapsedNanos) {
            if (elapsedNanos >= TimeUnit.MILLISECONDS.toNanos(150)) splitGitStatus = true;
        }
    }

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

        static GitRead readIncremental(Path projectRoot, Map<String, String> prior,
                                       FingerprintCache cache) {
            long statusStarted = System.nanoTime();
            IncrementalStatus status;
            if (cache != null && cache.splitGitStatus()) {
                status = splitIncrementalStatus(projectRoot);
            } else {
                String fullStatus = git(projectRoot, "status", "--porcelain=v2", "--branch");
                long probeNanos = System.nanoTime() - statusStarted;
                if (cache != null) cache.preferSplitGitStatus(probeNanos);
                status = fullStatus == null ? null : new IncrementalStatus(fullStatus, false);
            }
            long statusNanos = System.nanoTime() - statusStarted;
            if (status == null) return new GitRead(null, statusNanos);

            String commit = null;
            String branch = null;
            boolean dirty = status.untracked();
            for (String line : status.tracked().split("\\R")) {
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

        private static IncrementalStatus splitIncrementalStatus(Path cwd) {
            Process tracked = null;
            Process untracked = null;
            try {
                tracked = startGit(cwd, "status", "--porcelain=v2", "--branch",
                        "--untracked-files=no");
                untracked = startGit(cwd, "ls-files", "--others", "--exclude-standard",
                        "--directory", "--no-empty-directory");
                CompletableFuture<String> trackedOutput = readOutput(tracked);
                CompletableFuture<String> untrackedOutput = readOutput(untracked);
                if (!tracked.waitFor(2, TimeUnit.SECONDS)
                        || !untracked.waitFor(2, TimeUnit.SECONDS)) {
                    return null;
                }
                String trackedText = trackedOutput.get(2, TimeUnit.SECONDS).trim();
                String untrackedText = untrackedOutput.get(2, TimeUnit.SECONDS).trim();
                if (tracked.exitValue() != 0 || untracked.exitValue() != 0) return null;
                return new IncrementalStatus(trackedText, !untrackedText.isBlank());
            } catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return null;
            } finally {
                if (tracked != null && tracked.isAlive()) tracked.destroyForcibly();
                if (untracked != null && untracked.isAlive()) untracked.destroyForcibly();
            }
        }

        private static Process startGit(Path cwd, String... args) throws IOException {
            java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
            cmd.add("git");
            cmd.addAll(List.of(args));
            return new ProcessBuilder(cmd)
                    .directory(cwd.toFile())
                    .redirectErrorStream(true)
                    .start();
        }

        private static CompletableFuture<String> readOutput(Process process) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });
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

        private record IncrementalStatus(String tracked, boolean untracked) {}
    }
}
