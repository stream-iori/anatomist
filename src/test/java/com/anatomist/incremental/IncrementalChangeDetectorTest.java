package com.anatomist.incremental;

import com.anatomist.model.FileCacheEntry;
import com.anatomist.store.FileCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IncrementalChangeDetectorTest {

    @Test
    void manifestInfersModifyAddDeleteAndRejectsEscapes(@TempDir Path project) throws Exception {
        Path changed = write(project, "src/A.java", "new");
        Path added = write(project, "src/B.java", "added");
        Path manifest = project.resolve("changed.txt");
        Files.writeString(manifest, "# delta\nsrc/A.java\nsrc/B.java\nsrc/Gone.java\n");
        Map<String, FileCacheEntry> cache = Map.of(
                "src/A.java", entry("src/A.java", FileCacheService.sha256OfString("old")),
                "src/Gone.java", entry("src/Gone.java", FileCacheService.sha256OfString("gone")));

        IncrementalChangeDetector.Detection result = IncrementalChangeDetector.fromManifest(
                project, manifest.toString(), List.of(changed, added), cache, null);

        assertEquals("manifest", result.mode());
        assertEquals(List.of("src/A.java"), result.scan().changes().changed);
        assertEquals(List.of("src/B.java"), result.scan().changes().added);
        assertEquals(List.of("src/Gone.java"), result.scan().changes().deleted);

        Files.writeString(manifest, "../outside.java\n");
        assertThrows(java.io.IOException.class, () -> IncrementalChangeDetector.fromManifest(
                project, manifest.toString(), List.of(changed), cache, null));
    }

    @Test
    void gitDetectsCleanNoopDirtyFileAndRevertUsingPriorDirtyPaths(@TempDir Path project)
            throws Exception {
        Path source = write(project, "src/A.java", "one");
        git(project, "init", "-q");
        git(project, "config", "user.email", "test@example.com");
        git(project, "config", "user.name", "Test");
        git(project, "add", ".");
        git(project, "commit", "-qm", "initial");
        String head = git(project, "rev-parse", "HEAD");
        String root = project.toAbsolutePath().normalize().toString();
        Map<String, FileCacheEntry> cleanCache = Map.of(
                "src/A.java", entry("src/A.java", FileCacheService.sha256(source)));
        Map<String, String> clean = Map.of("source_git_root", root,
                "source_git_commit", head, "source_git_dirty", "false");

        IncrementalChangeDetector.Detection noop = IncrementalChangeDetector.fromGit(
                project, List.of(source), cleanCache, clean, null);
        assertNotNull(noop);
        assertTrue(noop.scan().changes().isEmpty());

        Files.writeString(source, "two");
        IncrementalChangeDetector.Detection dirty = IncrementalChangeDetector.fromGit(
                project, List.of(source), cleanCache, clean, null);
        assertEquals(List.of("src/A.java"), dirty.scan().changes().changed);

        Map<String, FileCacheEntry> dirtyCache = Map.of(
                "src/A.java", entry("src/A.java", FileCacheService.sha256(source)));
        Files.writeString(source, "one");
        Map<String, String> dirtyPrior = Map.of("source_git_root", root,
                "source_git_commit", head, "source_git_dirty", "true",
                "source_git_dirty_paths", "src/A.java");
        IncrementalChangeDetector.Detection reverted = IncrementalChangeDetector.fromGit(
                project, List.of(source), dirtyCache, dirtyPrior, null);
        assertEquals(List.of("src/A.java"), reverted.scan().changes().changed);
    }

    private static Path write(Path root, String relative, String content) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private static FileCacheEntry entry(String path, String hash) {
        return new FileCacheEntry(path, hash, FileCacheService.CURRENT_SCHEMA_VERSION,
                "now", 0, 0);
    }

    private static String git(Path root, String... args) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(root.toFile()).start();
        assertEquals(0, process.waitFor(), new String(process.getErrorStream().readAllBytes()));
        return new String(process.getInputStream().readAllBytes()).trim();
    }
}
