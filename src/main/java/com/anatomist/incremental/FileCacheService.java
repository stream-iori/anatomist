package com.anatomist.incremental;

import com.anatomist.model.FileCacheEntry;

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

    public static final int CURRENT_SCHEMA_VERSION = 1;

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

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
