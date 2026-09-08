package com.anatomist.version;

import com.anatomist.store.FileCacheService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Git plumbing. Never checks out or resets the caller's working tree. */
public record GitRepository(Path project, Path root, Path commonDirectory, Path projectRelative) {
    public static GitRepository open(Path project) {
        try {
            Path actual = project.toRealPath();
            Path root = Path.of(text(actual, "rev-parse", "--show-toplevel")).toRealPath();
            Path common = Path.of(text(actual, "rev-parse", "--path-format=absolute", "--git-common-dir")).toRealPath();
            return new GitRepository(actual, root, common, root.relativize(actual));
        } catch (IOException failure) {
            throw new SnapshotException("GIT_UNAVAILABLE", "Cannot open Git project: " + project, failure);
        }
    }
    public String key() {
        return FileCacheService.sha256OfString(commonDirectory + "\n" + projectRelative).substring(0, 24);
    }
    public String checkoutKey() { return FileCacheService.sha256OfString(root.toString()); }
    public String commit(String ref) {
        return text(root, "rev-parse", "--verify", "--end-of-options", ref + "^{commit}");
    }
    public String mergeBase(String a, String b) { return text(root, "merge-base", a, b); }
    public List<String> ancestors(String sha) {
        return text(root, "rev-list", "--topo-order", sha).lines().toList();
    }
    public void requireNoConflicts() {
        if (bytes(root, "ls-files", "-u", "-z").length != 0)
            throw new SnapshotException("WORKTREE_CONFLICT", "Resolve Git merge conflicts before capturing WORKTREE");
    }
    public void materialize(String sha, Path destination) {
        text(root, "worktree", "add", "--detach", destination.toString(), sha);
    }
    public void removeMaterialization(Path destination) {
        text(root, "worktree", "remove", "--force", destination.toString());
    }
    public static String text(Path directory, String... args) {
        return new String(bytes(directory, args), StandardCharsets.UTF_8).strip();
    }
    public static byte[] bytes(Path directory, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(args));
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process process = builder.start();
            process.getOutputStream().close();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<byte[]> out = executor.submit(() -> process.getInputStream().readAllBytes());
                Future<byte[]> err = executor.submit(() -> process.getErrorStream().readAllBytes());
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new SnapshotException("GIT_TIMEOUT", "Git operation timed out: " + args[0]);
                }
                byte[] result = out.get();
                String error = new String(err.get(), StandardCharsets.UTF_8).strip();
                if (process.exitValue() != 0)
                    throw new SnapshotException("GIT_FAILED", "git " + args[0] + ": " + error);
                return result;
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new SnapshotException("GIT_INTERRUPTED", "Git operation interrupted", failure);
        } catch (IOException | ExecutionException failure) {
            throw new SnapshotException("GIT_UNAVAILABLE", "Cannot execute Git", failure);
        }
    }
}
