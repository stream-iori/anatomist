package com.anatomist.incremental;

import com.anatomist.model.FileCacheEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileCacheServiceTest {

    @Test
    void testDetectChangedFiles(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("A.java");
        Files.writeString(a, "class A { int v = 2; }");

        FileCacheService svc = new FileCacheService();
        Map<String, String> disk = svc.computeFileHashes(tmp, List.of(a));

        Map<String, FileCacheEntry> cache = new HashMap<>();
        cache.put("A.java", new FileCacheEntry("A.java", "oldhash", 1, "x", 0, 0, 0, null));

        FileCacheService.Changes ch = svc.detectChanges(disk, cache);

        assertTrue(ch.changed.contains("A.java"), "A.java should be detected as changed");
        assertTrue(ch.added.isEmpty());
        assertTrue(ch.deleted.isEmpty());
    }

    @Test
    void testDetectNewFiles(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("New.java");
        Files.writeString(a, "class New {}");

        FileCacheService svc = new FileCacheService();
        Map<String, String> disk = svc.computeFileHashes(tmp, List.of(a));
        Map<String, FileCacheEntry> cache = new HashMap<>();

        FileCacheService.Changes ch = svc.detectChanges(disk, cache);

        assertTrue(ch.added.contains("New.java"));
        assertTrue(ch.changed.isEmpty());
        assertTrue(ch.deleted.isEmpty());
    }

    @Test
    void testDetectDeletedFiles(@TempDir Path tmp) {
        FileCacheService svc = new FileCacheService();
        Map<String, String> disk = new HashMap<>();
        Map<String, FileCacheEntry> cache = new HashMap<>();
        cache.put("Gone.java", new FileCacheEntry("Gone.java", "h", 1, "x", 0, 0, 0, null));

        FileCacheService.Changes ch = svc.detectChanges(disk, cache);

        assertTrue(ch.deleted.contains("Gone.java"));
        assertTrue(ch.changed.isEmpty());
        assertTrue(ch.added.isEmpty());
    }
}
