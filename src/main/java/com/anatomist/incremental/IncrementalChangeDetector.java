package com.anatomist.incremental;

import com.anatomist.core.IndexTimings;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.store.FileCacheService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Authoritative candidate sources for incremental indexing, before the safe scan fallback. */
public final class IncrementalChangeDetector {
    private static final long GIT_TIMEOUT_SECONDS = 2;

    private IncrementalChangeDetector() { }

    public record Detection(String mode, int candidateFiles,
                            FileCacheService.CandidateScan scan,
                            Map<String, String> gitMetadata) { }

    public static Detection fromManifest(Path projectRoot, String manifest,
                                         List<Path> inventory,
                                         Map<String, FileCacheEntry> cache,
                                         IndexTimings timings) throws IOException {
        long started = System.nanoTime();
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        BufferedReader reader = "-".equals(manifest)
                ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                : Files.newBufferedReader(Path.of(manifest), StandardCharsets.UTF_8);
        try (reader) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) continue;
                candidates.add(validateRelative(projectRoot, value));
            }
        }
        Detection detection = build("manifest", projectRoot, inventory, cache, candidates, true,
                Map.of());
        addTiming(timings, "manifest_delta", started);
        return detection;
    }

    public static Detection fromGit(Path projectRoot, List<Path> inventory,
                                    Map<String, FileCacheEntry> cache,
                                    Map<String, String> prior,
                                    IndexTimings timings) {
        long started = System.nanoTime();
        try {
            Path root = projectRoot.toAbsolutePath().normalize();
            String priorRoot = prior.get("source_git_root");
            String priorCommit = prior.get("source_git_commit");
            if (priorRoot == null || priorCommit == null
                    || !root.equals(Path.of(priorRoot).toAbsolutePath().normalize())) {
                return null;
            }
            boolean priorDirty = Boolean.parseBoolean(prior.getOrDefault("source_git_dirty", "true"));
            String priorDirtyPaths = prior.get("source_git_dirty_paths");
            if (priorDirty && (priorDirtyPaths == null || priorDirtyPaths.isBlank())) return null;
            String head = gitText(root, List.of("rev-parse", "HEAD"));
            if (!priorCommit.equals(head)) return null;
            LinkedHashSet<String> known = new LinkedHashSet<>(cache.keySet());
            for (Path path : inventory) known.add(relative(root, path));
            if (hasIgnoredFiles(root, known)) return null;

            byte[] raw = gitBytes(root, List.of(
                    "status", "--porcelain=v1", "-z", "--untracked-files=all", "--", "."), null);
            if (raw == null) return null;
            List<String> records = splitZero(raw);
            LinkedHashSet<String> candidates = new LinkedHashSet<>();
            for (int i = 0; i < records.size(); i++) {
                String record = records.get(i);
                if (record.length() < 4) continue;
                String status = record.substring(0, 2);
                candidates.add(validateRelative(root, record.substring(3)));
                if ((status.charAt(0) == 'R' || status.charAt(0) == 'C')
                        && i + 1 < records.size()) {
                    candidates.add(validateRelative(root, records.get(++i)));
                }
            }
            LinkedHashSet<String> currentDirtyPaths = new LinkedHashSet<>(candidates);
            Map<String, String> gitMetadata = new LinkedHashMap<>();
            gitMetadata.put("source_git_root", root.toString());
            gitMetadata.put("source_git_commit", head);
            copy(prior, gitMetadata, "source_git_branch");
            copy(prior, gitMetadata, "source_git_commit_time");
            copy(prior, gitMetadata, "source_git_remote_origin_url");
            gitMetadata.put("source_git_dirty", String.valueOf(!currentDirtyPaths.isEmpty()));
            gitMetadata.put("source_git_dirty_paths", String.join("\n", currentDirtyPaths));
            if (priorDirtyPaths != null) {
                for (String path : priorDirtyPaths.split("\\R")) {
                    if (!path.isBlank()) candidates.add(validateRelative(root, path));
                }
            }
            return build("git", root, inventory, cache, candidates, false, gitMetadata);
        } catch (Exception unavailable) {
            return null;
        } finally {
            addTiming(timings, "git_delta", started);
        }
    }

    private static Detection build(String mode, Path projectRoot, List<Path> inventory,
                                   Map<String, FileCacheEntry> cache, Set<String> candidates,
                                   boolean strict, Map<String, String> gitMetadata) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        Map<String, Path> current = new LinkedHashMap<>();
        for (Path supplied : inventory) current.put(relative(root, supplied), supplied);
        if (strict) {
            for (String candidate : candidates) {
                if (!current.containsKey(candidate) && !cache.containsKey(candidate)) {
                    throw new IOException("changed-files entry is neither indexed nor cached: " + candidate);
                }
            }
        }
        Map<String, String> hashes = new LinkedHashMap<>();
        List<String> changed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        for (Map.Entry<String, Path> entry : current.entrySet()) {
            String path = entry.getKey();
            FileCacheEntry prior = cache.get(path);
            if (!candidates.contains(path) && prior != null) {
                hashes.put(path, prior.hash());
                continue;
            }
            String hash = FileCacheService.sha256(entry.getValue());
            hashes.put(path, hash);
            if (prior == null) added.add(path);
            else if (!prior.hash().equals(hash)) changed.add(path);
        }
        for (String candidate : candidates) {
            if (cache.containsKey(candidate) && !current.containsKey(candidate)) deleted.add(candidate);
        }
        return new Detection(mode, candidates.size(), new FileCacheService.CandidateScan(
                new FileCacheService.Changes(changed, added, deleted), hashes, List.of()),
                Map.copyOf(gitMetadata));
    }

    private static void copy(Map<String, String> from, Map<String, String> to, String key) {
        String value = from.get(key);
        if (value != null) to.put(key, value);
    }

    private static boolean hasIgnoredFiles(Path root, Set<String> paths) throws IOException {
        if (paths.isEmpty()) return false;
        String input = String.join("\0", paths) + "\0";
        byte[] ignored = gitBytes(root,
                List.of("check-ignore", "-z", "--stdin"), input.getBytes(StandardCharsets.UTF_8));
        return ignored == null || ignored.length > 0;
    }

    private static String validateRelative(Path root, String supplied) throws IOException {
        Path path = Path.of(supplied.replace('\\', '/'));
        if (path.isAbsolute()) throw new IOException("changed-files entry must be project-relative: " + supplied);
        Path normalized = path.normalize();
        if (normalized.toString().isEmpty() || normalized.startsWith("..")) {
            throw new IOException("changed-files entry escapes the project: " + supplied);
        }
        Path resolved = root.resolve(normalized).normalize();
        if (!resolved.startsWith(root)) throw new IOException("changed-files entry escapes the project: " + supplied);
        return normalized.toString().replace('\\', '/');
    }

    private static String relative(Path root, Path supplied) {
        return root.relativize(supplied.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String gitText(Path root, List<String> args) throws IOException {
        byte[] output = gitBytes(root, args, null);
        return output == null ? null : new String(output, StandardCharsets.UTF_8).trim();
    }

    private static byte[] gitBytes(Path root, List<String> args, byte[] input) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(args);
        Process process = new ProcessBuilder(command).directory(root.toFile())
                .redirectErrorStream(true).start();
        if (input != null) {
            process.getOutputStream().write(input);
            process.getOutputStream().close();
        }
        try {
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            byte[] output = process.getInputStream().readAllBytes();
            // check-ignore uses 1 for "no matches"; all other commands require success.
            boolean checkIgnore = !args.isEmpty() && "check-ignore".equals(args.getFirst());
            return process.exitValue() == 0 || (checkIgnore && process.exitValue() == 1)
                    ? output : null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return null;
        }
    }

    private static List<String> splitZero(byte[] bytes) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != 0) continue;
            if (i > start) out.add(new String(bytes, start, i - start, StandardCharsets.UTF_8));
            start = i + 1;
        }
        if (start < bytes.length) out.add(new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8));
        return out;
    }

    private static void addTiming(IndexTimings timings, String phase, long started) {
        if (timings != null) timings.addNanos(phase, System.nanoTime() - started);
    }
}
