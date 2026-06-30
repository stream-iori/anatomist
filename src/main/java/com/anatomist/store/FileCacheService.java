package com.anatomist.store;

import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.model.Node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        if (files == null || files.isEmpty()) return List.of();
        Path rootAbs = projectRoot.toAbsolutePath().normalize();
        List<String> relFiles = new ArrayList<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Path file : files) {
            Path abs = file.toAbsolutePath().normalize();
            String rel = relativize(rootAbs, abs);
            relFiles.add(rel);
            hashes.put(rel, sha256(abs));
        }
        return buildEntries(relFiles, hashes, stats, indexedAt);
    }

    public static List<FileCacheEntry> buildEntries(List<String> relFiles,
                                                    Map<String, String> hashes,
                                                    Map<String, SourceFileStats> stats,
                                                    String indexedAt) {
        if (relFiles == null || relFiles.isEmpty()) return List.of();
        List<FileCacheEntry> entries = new ArrayList<>();
        for (String rel : relFiles) {
            String hash = hashes == null ? null : hashes.get(rel);
            if (hash == null) continue;
            SourceFileStats count = stats == null
                    ? new SourceFileStats(0, 0)
                    : stats.getOrDefault(rel, new SourceFileStats(0, 0));
            entries.add(new FileCacheEntry(rel, hash, CURRENT_SCHEMA_VERSION,
                    indexedAt, count.nodeCount(), count.edgeCount()));
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
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
