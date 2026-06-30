package com.anatomist.core;

import com.anatomist.store.FileCacheService;
import com.anatomist.store.SqliteStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class ProjectMetadata {

    private ProjectMetadata() {}

    static void write(SqliteStore store, IndexConfig cfg, int dropped, int rebound, int wired) {
        put(store, "source_root", cfg.projectRoot().toAbsolutePath().normalize().toString());
        put(store, "source_paths", joinPaths(cfg.sourcePaths()));
        put(store, "indexed_at", Instant.now().toString());
        put(store, "dropped_dangling_edges", String.valueOf(dropped));
        put(store, "rebound_external_edges", String.valueOf(rebound));
        put(store, "wiring_resolved_edges", String.valueOf(wired));
        put(store, "java_version", String.valueOf(cfg.javaVersion()));
        put(store, "classpath_hash",
                FileCacheService.sha256OfString(IndexOrchestrator.classpathFingerprint(
                        cfg.classpathEntries(), cfg.classpathOverride())));
        put(store, "index_version", String.valueOf(FileCacheService.CURRENT_SCHEMA_VERSION));

        GitSnapshot git = GitSnapshot.read(cfg.projectRoot());
        if (git == null) return;
        put(store, "source_git_root", git.root());
        put(store, "source_git_commit", git.commit());
        put(store, "source_git_branch", git.branch());
        put(store, "source_git_dirty", String.valueOf(git.dirty()));
        put(store, "source_git_commit_time", git.commitTime());
        put(store, "source_git_remote_origin_url", git.remoteOriginUrl());
    }

    private static void put(SqliteStore store, String key, String value) {
        if (value != null) store.upsertProjectMeta(key, value);
    }

    private static String joinPaths(List<Path> paths) {
        if (paths == null || paths.isEmpty()) return "";
        return String.join(java.io.File.pathSeparator, paths.stream()
                .map(p -> p.toAbsolutePath().normalize().toString())
                .toList());
    }

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
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                return p.exitValue() == 0 ? out : null;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return null;
            }
        }
    }
}
