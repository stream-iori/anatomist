package com.anatomist.incremental;

import com.anatomist.model.FileCacheEntry;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.anatomist.store.FileCacheService;
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
        cache.put("A.java", new FileCacheEntry("A.java", "oldhash", 1, "x", 0, 0));

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
        cache.put("Gone.java", new FileCacheEntry("Gone.java", "h", 1, "x", 0, 0));

        FileCacheService.Changes ch = svc.detectChanges(disk, cache);

        assertTrue(ch.deleted.contains("Gone.java"));
        assertTrue(ch.changed.isEmpty());
        assertTrue(ch.added.isEmpty());
    }

    @Test
    void countBySourceFileCombinesMultipleExtractionResults() {
        ExtractionResult java = new ExtractionResult();
        java.nodes.add(node("p.A", "src/A.java"));
        java.nodes.add(node("p.A#m()", "src/A.java"));
        java.edges.add(edge("p.A", "p.A#m()", "src/A.java"));

        ExtractionResult xml = new ExtractionResult();
        xml.nodes.add(node("bean:a", "src/beans.xml"));
        xml.edges.add(edge("bean:a", "p.A", "src/beans.xml"));

        Map<String, FileCacheService.SourceFileStats> stats =
                FileCacheService.countBySourceFile(java, xml);

        assertEquals(new FileCacheService.SourceFileStats(2, 1), stats.get("src/A.java"));
        assertEquals(new FileCacheService.SourceFileStats(1, 1), stats.get("src/beans.xml"));
    }

    @Test
    void buildEntriesFromRelativeFilesUsesHashesAndStats() {
        Map<String, String> hashes = Map.of("src/A.java", "hash-a");
        Map<String, FileCacheService.SourceFileStats> stats = Map.of(
                "src/A.java", new FileCacheService.SourceFileStats(2, 3));

        List<FileCacheEntry> entries = FileCacheService.buildEntries(
                List.of("src/A.java", "src/Missing.java"), hashes, stats, "now");

        assertEquals(1, entries.size());
        FileCacheEntry entry = entries.get(0);
        assertEquals("src/A.java", entry.sourceFile());
        assertEquals("hash-a", entry.hash());
        assertEquals(FileCacheService.CURRENT_SCHEMA_VERSION, entry.schemaVersion());
        assertEquals(2, entry.nodeCount());
        assertEquals(3, entry.edgeCount());
    }

    private static Node node(String id, String sourceFile) {
        Node n = new Node();
        n.id = id;
        n.sourceFile = sourceFile;
        return n;
    }

    private static Edge edge(String sourceId, String targetId, String sourceFile) {
        Edge e = new Edge();
        e.sourceId = sourceId;
        e.targetId = targetId;
        e.sourceFile = sourceFile;
        return e;
    }
}
