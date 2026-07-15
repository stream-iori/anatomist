package com.anatomist.core;

import com.anatomist.model.FileCacheEntry;
import com.anatomist.store.SqliteStore;
import com.anatomist.test.CliTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMetadataTest {

    @Test
    void sourceSnapshotFingerprintIsPortableAcrossCheckoutRoots(@TempDir Path tmp) throws Exception {
        Path first = Files.createDirectories(tmp.resolve("user-a/payment-service"));
        Path second = Files.createDirectories(tmp.resolve("user-b/payment-service"));
        List<SourceRoot> firstRoots = List.of(new SourceRoot(
                first.resolve("src/main/java"), ".", SourceScope.MAIN));
        List<SourceRoot> secondRoots = List.of(new SourceRoot(
                second.resolve("src/main/java"), ".", SourceScope.MAIN));
        Map<String, FileCacheEntry> firstCache = cache(
                entry("src/main/java/com/example/A.java", "aaa"),
                entry("src/main/java/com/example/B.java", "bbb"));
        Map<String, FileCacheEntry> secondCache = cache(
                entry("src/main/java/com/example/B.java", "bbb"),
                entry("src/main/java/com/example/A.java", "aaa"));

        assertEquals(
                ProjectMetadata.sourceSnapshotFingerprint(first, firstRoots, firstCache),
                ProjectMetadata.sourceSnapshotFingerprint(second, secondRoots, secondCache));
    }

    @Test
    void sourceSnapshotFingerprintChangesWithContentPathOrScope(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("payment-service"));
        List<SourceRoot> main = List.of(new SourceRoot(
                project.resolve("src/main/java"), ".", SourceScope.MAIN));
        List<SourceRoot> test = List.of(new SourceRoot(
                project.resolve("src/main/java"), ".", SourceScope.TEST));
        String baseline = ProjectMetadata.sourceSnapshotFingerprint(project, main,
                cache(entry("src/main/java/com/example/A.java", "aaa")));

        assertNotEquals(baseline, ProjectMetadata.sourceSnapshotFingerprint(project, main,
                cache(entry("src/main/java/com/example/A.java", "changed"))));
        assertNotEquals(baseline, ProjectMetadata.sourceSnapshotFingerprint(project, main,
                cache(entry("src/main/java/com/example/Renamed.java", "aaa"))));
        assertNotEquals(baseline, ProjectMetadata.sourceSnapshotFingerprint(project, test,
                cache(entry("src/main/java/com/example/A.java", "aaa"))));
    }

    @Test
    void indexEnvironmentTracksClasspathArtifactStats(@TempDir Path tmp) throws Exception {
        Path source = Files.createDirectories(tmp.resolve("src/main/java"));
        Path jar = tmp.resolve("dependency.jar");
        Files.writeString(jar, "one");
        List<SourceRoot> roots = List.of(new SourceRoot(source, ".", SourceScope.MAIN));
        String before = IndexEnvironmentFingerprint.snapshot(
                roots, 17, "detected", List.of(jar), "", false).hash();

        Files.writeString(jar, "changed-content");
        String after = IndexEnvironmentFingerprint.snapshot(
                roots, 17, "detected", List.of(jar), "", false).hash();

        assertNotEquals(before, after);
    }

    @Test
    void incrementalGitMetadataTracksDirtyRevertAndDetachedHead(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        git(project, "init", "-q");
        git(project, "config", "user.name", "Anatomist Test");
        git(project, "config", "user.email", "anatomist@example.test");
        git(project, "add", ".");
        git(project, "commit", "-qm", "initial");

        Path db = tmp.resolve("index.db");
        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--output", db.toString());
        String initialCommit;
        try (SqliteStore store = new SqliteStore(db)) {
            initialCommit = store.readProjectMeta("source_git_commit").orElseThrow();
            assertEquals("false", store.readProjectMeta("source_git_dirty").orElseThrow());
        }

        Path source = project.resolve("src/main/java/p/A.java");
        String original = Files.readString(source);
        Files.writeString(source, original + "\n// dirty\n");
        Files.writeString(project.resolve("untracked.txt"), "untracked\n");
        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--incremental", "--output", db.toString());
        try (SqliteStore store = new SqliteStore(db)) {
            assertEquals(initialCommit,
                    store.readProjectMeta("source_git_commit").orElseThrow());
            assertEquals("true", store.readProjectMeta("source_git_dirty").orElseThrow());
        }

        Files.writeString(source, original);
        Files.delete(project.resolve("untracked.txt"));
        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--incremental", "--output", db.toString());
        try (SqliteStore store = new SqliteStore(db)) {
            assertEquals("false", store.readProjectMeta("source_git_dirty").orElseThrow());
        }

        git(project, "checkout", "--detach", "-q");
        Files.writeString(source, original + "\n// committed on detached head\n");
        git(project, "add", source.toString());
        git(project, "commit", "-qm", "detached update");
        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--incremental", "--output", db.toString());
        try (SqliteStore store = new SqliteStore(db)) {
            String updatedCommit = store.readProjectMeta("source_git_commit").orElseThrow();
            assertNotEquals(initialCommit, updatedCommit);
            assertEquals("HEAD", store.readProjectMeta("source_git_branch").orElseThrow());
            assertEquals("false", store.readProjectMeta("source_git_dirty").orElseThrow());
            assertTrue(store.readProjectMeta("source_git_commit_time").isPresent());
        }
    }

    private static FileCacheEntry entry(String path, String hash) {
        return new FileCacheEntry(path, hash, 5, "ignored", 0, 0);
    }

    private static Map<String, FileCacheEntry> cache(FileCacheEntry... entries) {
        Map<String, FileCacheEntry> result = new LinkedHashMap<>();
        for (FileCacheEntry entry : entries) result.put(entry.sourceFile(), entry);
        return result;
    }

    private static void git(Path cwd, String... args) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
}
