package com.anatomist.store;

import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.model.Node;
import com.anatomist.core.SpringBeanParser;
import com.anatomist.core.IndexTimings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

public class FileCacheService {

    public static final int CURRENT_SCHEMA_VERSION = IndexSchema.VERSION;

    public record SourceFileStats(int nodeCount, int edgeCount) {
        SourceFileStats plusNodes(int count) {
            return new SourceFileStats(nodeCount + count, edgeCount);
        }

        SourceFileStats plusEdges(int count) {
            return new SourceFileStats(nodeCount, edgeCount + count);
        }
    }

    public static final class Changes {
        public final List<String> changed;
        public final List<String> added;
        public final List<String> deleted;

        public Changes(List<String> changed, List<String> added, List<String> deleted) {
            this.changed = changed;
            this.added = added;
            this.deleted = deleted;
        }

        public boolean isEmpty() {
            return changed.isEmpty() && added.isEmpty() && deleted.isEmpty();
        }
    }

    public record CandidateScan(Changes changes, Map<String, String> diskHashes,
                                List<FileCacheEntry> statRefreshes) {}

    private record FileState(long size, long mtimeNs) {}

    /**
     * Reconcile a complete Watch event batch without reading every cached file.
     * Cached hashes seed the disk view; candidate paths are then classified from
     * their final on-disk state.
     */
    public CandidateScan detectCandidateChanges(Path projectRoot,
                                                Set<String> candidates,
                                                Map<String, FileCacheEntry> cache,
                                                boolean springXml) {
        return detectCandidateChanges(projectRoot, candidates, cache, springXml, null);
    }

    public CandidateScan detectCandidateChanges(Path projectRoot,
                                                Set<String> candidates,
                                                Map<String, FileCacheEntry> cache,
                                                boolean springXml,
                                                IndexTimings timings) {
        Map<String, String> diskHashes = new LinkedHashMap<>();
        cache.forEach((path, entry) -> diskHashes.put(path, entry.hash()));
        List<String> changed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) {
            return new CandidateScan(new Changes(changed, added, deleted), diskHashes, List.of());
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        for (String candidate : new LinkedHashSet<>(candidates)) {
            if (candidate == null || candidate.isBlank()) continue;
            Path supplied = Path.of(candidate);
            Path absolute = supplied.isAbsolute() ? supplied : root.resolve(supplied);
            absolute = absolute.toAbsolutePath().normalize();
            String relative;
            try {
                relative = root.relativize(absolute).toString();
            } catch (IllegalArgumentException ex) {
                continue;
            }
            FileCacheEntry prior = cache.get(relative);
            boolean javaSource = relative.endsWith(".java") && Files.isRegularFile(absolute);
            boolean springSource = springXml && relative.endsWith(".xml")
                    && SpringBeanParser.isSpringBeansFile(absolute);
            boolean indexableNow = javaSource || springSource;
            if (!indexableNow) {
                diskHashes.remove(relative);
                if (prior != null) deleted.add(relative);
                continue;
            }
            long hashStarted = startTiming(timings);
            String hash = sha256(absolute);
            stopTiming(timings, "file_hash", hashStarted);
            diskHashes.put(relative, hash);
            if (prior == null) {
                added.add(relative);
            } else if (!prior.hash().equals(hash)) {
                changed.add(relative);
            }
        }
        return new CandidateScan(new Changes(changed, added, deleted), diskHashes, List.of());
    }

    /**
     * Standalone incremental scan. Stable size/mtime pairs reuse the prior SHA;
     * Watch never calls this path because event candidates are always hashed.
     */
    public CandidateScan detectChangesFast(Path projectRoot, List<Path> sourceFiles,
                                           Map<String, FileCacheEntry> cache,
                                           boolean verifyContent, IndexTimings timings) {
        Map<String, String> diskHashes = new LinkedHashMap<>();
        List<String> changed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<FileCacheEntry> refreshes = new ArrayList<>();
        Path root = projectRoot.toAbsolutePath().normalize();
        for (Path supplied : sourceFiles) {
            Path absolute = supplied.toAbsolutePath().normalize();
            String relative = relativize(root, absolute);
            long statStarted = startTiming(timings);
            FileState state = state(absolute);
            stopTiming(timings, "file_stat", statStarted);
            FileCacheEntry prior = cache.get(relative);
            boolean statMatches = prior != null && prior.fileSize() == state.size()
                    && prior.fileMtimeNs() == state.mtimeNs();
            String hash;
            if (!verifyContent && statMatches) {
                hash = prior.hash();
            } else {
                long hashStarted = startTiming(timings);
                hash = sha256(absolute);
                stopTiming(timings, "file_hash", hashStarted);
            }
            diskHashes.put(relative, hash);
            if (prior == null) {
                added.add(relative);
            } else if (!prior.hash().equals(hash)) {
                changed.add(relative);
            } else if (!statMatches) {
                refreshes.add(copyWithState(prior, state));
            }
        }
        List<String> deleted = new ArrayList<>();
        for (String cached : cache.keySet()) {
            if (!diskHashes.containsKey(cached)) deleted.add(cached);
        }
        return new CandidateScan(new Changes(changed, added, deleted), diskHashes,
                List.copyOf(refreshes));
    }

    public Map<String, String> computeFileHashes(Path projectRoot, List<Path> sourceFiles) {
        Map<String, String> out = new LinkedHashMap<>();
        Path rootAbs = projectRoot.toAbsolutePath().normalize();
        for (Path f : sourceFiles) {
            Path abs = f.toAbsolutePath().normalize();
            String key;
            try {
                key = rootAbs.relativize(abs).toString();
            } catch (IllegalArgumentException ex) {
                key = abs.toString();
            }
            out.put(key, sha256(abs));
        }
        return out;
    }

    public Changes detectChanges(Map<String, String> diskHashes, Map<String, FileCacheEntry> cache) {
        List<String> changed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        Set<String> diskSet = diskHashes.keySet();
        Set<String> cacheSet = cache.keySet();

        for (Map.Entry<String, String> e : diskHashes.entrySet()) {
            FileCacheEntry cached = cache.get(e.getKey());
            if (cached == null) {
                added.add(e.getKey());
            } else if (!cached.hash().equals(e.getValue())) {
                changed.add(e.getKey());
            }
        }
        for (String cf : cacheSet) {
            if (!diskSet.contains(cf)) deleted.add(cf);
        }
        return new Changes(changed, added, deleted);
    }

    public static Map<String, SourceFileStats> countBySourceFile(ExtractionResult... results) {
        Map<String, SourceFileStats> out = new LinkedHashMap<>();
        if (results == null) return out;
        for (ExtractionResult result : results) {
            if (result == null) continue;
            for (Node n : result.nodes) {
                if (n.sourceFile == null) continue;
                out.merge(n.sourceFile, new SourceFileStats(1, 0),
                        (a, b) -> new SourceFileStats(a.nodeCount + b.nodeCount, a.edgeCount + b.edgeCount));
            }
            for (Edge e : result.edges) {
                if (e.sourceFile == null) continue;
                out.merge(e.sourceFile, new SourceFileStats(0, 1),
                        (a, b) -> new SourceFileStats(a.nodeCount + b.nodeCount, a.edgeCount + b.edgeCount));
            }
        }
        return out;
    }

    public static List<FileCacheEntry> buildEntries(Path projectRoot,
                                                    List<Path> files,
                                                    Map<String, SourceFileStats> stats,
                                                    String indexedAt) {
        return buildEntries(projectRoot, files, stats, indexedAt, Map.of());
    }

    public static List<FileCacheEntry> buildEntries(Path projectRoot,
                                                    List<Path> files,
                                                    Map<String, SourceFileStats> stats,
                                                    String indexedAt,
                                                    Map<String, String> contractHashes) {
        if (files == null || files.isEmpty()) return List.of();
        Path rootAbs = projectRoot.toAbsolutePath().normalize();
        List<String> relFiles = new ArrayList<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        Map<String, FileState> states = new LinkedHashMap<>();
        for (Path file : files) {
            Path abs = file.toAbsolutePath().normalize();
            String rel = relativize(rootAbs, abs);
            relFiles.add(rel);
            hashes.put(rel, sha256(abs));
            states.put(rel, state(abs));
        }
        return buildEntries(relFiles, hashes, states, stats, indexedAt, contractHashes);
    }

    public static List<FileCacheEntry> buildEntries(Path projectRoot,
                                                    List<String> relFiles,
                                                    Map<String, String> hashes,
                                                    Map<String, SourceFileStats> stats,
                                                    String indexedAt,
                                                    Map<String, String> contractHashes) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Map<String, FileState> states = new LinkedHashMap<>();
        for (String rel : relFiles) {
            Path path = Path.of(rel);
            if (!path.isAbsolute()) path = root.resolve(path);
            if (Files.isRegularFile(path)) states.put(rel, state(path));
        }
        return buildEntries(relFiles, hashes, states, stats, indexedAt, contractHashes);
    }

    public static List<FileCacheEntry> buildEntries(List<String> relFiles,
                                                    Map<String, String> hashes,
                                                    Map<String, SourceFileStats> stats,
                                                    String indexedAt) {
        return buildEntries(relFiles, hashes, Map.of(), stats, indexedAt, Map.of());
    }

    private static List<FileCacheEntry> buildEntries(List<String> relFiles,
                                                     Map<String, String> hashes,
                                                     Map<String, FileState> states,
                                                     Map<String, SourceFileStats> stats,
                                                     String indexedAt,
                                                     Map<String, String> contractHashes) {
        if (relFiles == null || relFiles.isEmpty()) return List.of();
        List<FileCacheEntry> entries = new ArrayList<>();
        for (String rel : relFiles) {
            String hash = hashes == null ? null : hashes.get(rel);
            if (hash == null) continue;
            SourceFileStats count = stats == null
                    ? new SourceFileStats(0, 0)
                    : stats.getOrDefault(rel, new SourceFileStats(0, 0));
            FileState state = states.getOrDefault(rel, new FileState(-1L, -1L));
            entries.add(new FileCacheEntry(rel, hash, CURRENT_SCHEMA_VERSION,
                    indexedAt, count.nodeCount(), count.edgeCount(), state.size(), state.mtimeNs(),
                    contractHashes == null ? "" : contractHashes.getOrDefault(rel, "")));
        }
        return entries;
    }

    public static String sha256(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(file);
            byte[] digest = md.digest(bytes);
            return toHex(digest);
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new RuntimeException("Failed to hash " + file, e);
        }
    }

    public static String sha256OfString(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return toHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static String relativize(Path rootAbs, Path abs) {
        try {
            return rootAbs.relativize(abs).toString();
        } catch (IllegalArgumentException ex) {
            return abs.toString();
        }
    }

    private static String toHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static FileState state(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            return new FileState(attrs.size(), attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS));
        } catch (IOException e) {
            throw new RuntimeException("Failed to stat " + path, e);
        }
    }

    private static FileCacheEntry copyWithState(FileCacheEntry prior, FileState state) {
        return new FileCacheEntry(prior.sourceFile(), prior.hash(), CURRENT_SCHEMA_VERSION,
                prior.lastIndexed(), prior.nodeCount(), prior.edgeCount(), state.size(), state.mtimeNs(),
                prior.contractHash());
    }

    private static long startTiming(IndexTimings timings) {
        return timings == null ? 0L : timings.start();
    }

    private static void stopTiming(IndexTimings timings, String phase, long started) {
        if (timings != null) timings.stop(phase, started);
    }
}
