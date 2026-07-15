package com.anatomist.incremental;

import com.anatomist.model.FileCacheEntry;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.anatomist.core.IndexTimings;
import com.anatomist.store.FileCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FileCacheServiceTest {

    @Test
    void fastScanReusesHashWhenSizeAndMtimeMatch(@TempDir Path tmp) throws Exception {
        Path source = tmp.resolve("A.java");
        Files.writeString(source, "class A {}");
        FileCacheEntry prior = FileCacheService.buildEntries(
                tmp, List.of(source), Map.of(), "now").get(0);
        IndexTimings timings = new IndexTimings();

        FileCacheService.CandidateScan scan = new FileCacheService().detectChangesFast(
                tmp, List.of(source), Map.of("A.java", prior), false, timings);

        assertTrue(scan.changes().isEmpty());
        assertTrue(scan.statRefreshes().isEmpty());
        assertTrue(timings.millis().containsKey("file_stat"));
        assertFalse(timings.millis().containsKey("file_hash"));
    }

    @Test
    void fastScanRefreshesStatWithoutReindexingUnchangedContent(@TempDir Path tmp) throws Exception {
        Path source = tmp.resolve("A.java");
        Files.writeString(source, "class A {}");
        FileCacheEntry prior = FileCacheService.buildEntries(
                tmp, List.of(source), Map.of(), "now").get(0);
        Files.setLastModifiedTime(source,
                FileTime.fromMillis(Files.getLastModifiedTime(source).toMillis() + 2_000));

        FileCacheService.CandidateScan scan = new FileCacheService().detectChangesFast(
                tmp, List.of(source), Map.of("A.java", prior), false, new IndexTimings());

        assertTrue(scan.changes().isEmpty());
        assertEquals(1, scan.statRefreshes().size());
        assertEquals(prior.hash(), scan.statRefreshes().get(0).hash());
        assertNotEquals(prior.fileMtimeNs(), scan.statRefreshes().get(0).fileMtimeNs());
    }

    @Test
    void verifyContentAndWatchCandidatesCatchRestoredTimestampChange(@TempDir Path tmp) throws Exception {
        Path source = tmp.resolve("A.java");
        Files.writeString(source, "class A { int v=1; }");
        FileCacheEntry prior = FileCacheService.buildEntries(
                tmp, List.of(source), Map.of(), "now").get(0);
        FileTime originalTime = Files.getLastModifiedTime(source);
        Files.writeString(source, "class A { int v=2; }");
        Files.setLastModifiedTime(source, originalTime);

        FileCacheService service = new FileCacheService();
        FileCacheService.CandidateScan trustedStat = service.detectChangesFast(
                tmp, List.of(source), Map.of("A.java", prior), false, null);
        FileCacheService.CandidateScan verified = service.detectChangesFast(
                tmp, List.of(source), Map.of("A.java", prior), true, null);
        FileCacheService.CandidateScan watched = service.detectCandidateChanges(
                tmp, Set.of("A.java"), Map.of("A.java", prior), false);

        assertTrue(trustedStat.changes().isEmpty(), "default CLI trusts a stable size/mtime pair");
        assertEquals(List.of("A.java"), verified.changes().changed);
        assertEquals(List.of("A.java"), watched.changes().changed);
    }

    @Test
    void candidateScanClassifiesFinalDiskStateWithoutDroppingUnchangedCache(@TempDir Path tmp) throws Exception {
        Path changed = tmp.resolve("Changed.java");
        Path added = tmp.resolve("Added.java");
        Files.writeString(changed, "class Changed { int v = 2; }");
        Files.writeString(added, "class Added {}");

        Map<String, FileCacheEntry> cache = new HashMap<>();
        cache.put("Changed.java", new FileCacheEntry("Changed.java", "old", 5, "x", 0, 0));
        cache.put("Deleted.java", new FileCacheEntry("Deleted.java", "gone", 5, "x", 0, 0));
        cache.put("Untouched.java", new FileCacheEntry("Untouched.java", "keep", 5, "x", 0, 0));

        FileCacheService.CandidateScan scan = new FileCacheService().detectCandidateChanges(
                tmp, Set.of("Changed.java", "Added.java", "Deleted.java"), cache, false);

        assertEquals(List.of("Changed.java"), scan.changes().changed);
        assertEquals(List.of("Added.java"), scan.changes().added);
        assertEquals(List.of("Deleted.java"), scan.changes().deleted);
        assertEquals("keep", scan.diskHashes().get("Untouched.java"));
    }

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
