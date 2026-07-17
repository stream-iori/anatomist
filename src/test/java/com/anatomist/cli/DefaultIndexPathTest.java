package com.anatomist.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Pin the collision-free, machine-local storage policy. */
class DefaultIndexPathTest {

    @Test
    void defaultForWrite_isUnderAnatomistHomeWithRepoName(@TempDir Path home,
                                                          @TempDir Path proj) {
        Path project = proj.resolve("my-app");
        ensureDir(project);
        Path got = DefaultIndexPath.forIndexWrite(project, home);
        assertEquals(home.resolve("indexes").resolve(DefaultIndexPath.repoKeyOf(project)).resolve("index.db"), got);
        assertTrue(got.getParent().getFileName().toString().matches("my-app-[0-9a-f]{12}"));
    }

    @Test
    void defaultForWrite_doesNotReuseLegacyInProjectDb(@TempDir Path home,
                                                        @TempDir Path proj) throws Exception {
        Path project = proj.resolve("my-app");
        ensureDir(project);
        Path legacy = project.resolve(".anatomist").resolve("index.db");
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, new byte[]{1});

        Path got = DefaultIndexPath.forIndexWrite(project, home);
        assertNotEquals(legacy, got);
        assertEquals(home.resolve("indexes").resolve(DefaultIndexPath.repoKeyOf(project)).resolve("index.db"), got);
    }

    @Test
    void defaultForRead_findsUnderAnatomistHomeWhenWriteWentThere(@TempDir Path home,
                                                                   @TempDir Path proj) throws Exception {
        Path project = proj.resolve("my-app");
        ensureDir(project);
        Path expected = home.resolve("indexes").resolve(DefaultIndexPath.repoKeyOf(project)).resolve("index.db");
        Files.createDirectories(expected.getParent());
        Files.write(expected, new byte[]{1});

        Path got = DefaultIndexPath.forQueryRead(project, home);
        assertEquals(expected, got);
    }

    @Test
    void defaultForRead_ignoresLegacyInProjectDb(@TempDir Path home,
                                                  @TempDir Path proj) throws Exception {
        Path project = proj.resolve("my-app");
        ensureDir(project);
        Path legacy = project.resolve(".anatomist").resolve("index.db");
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, new byte[]{1});

        Path got = DefaultIndexPath.forQueryRead(project, home);
        assertNotEquals(legacy, got);
        assertEquals(home.resolve("indexes").resolve(DefaultIndexPath.repoKeyOf(project)).resolve("index.db"), got);
    }

    @Test
    void defaultForRead_returnsExpectedPathEvenWhenMissing(@TempDir Path home,
                                                            @TempDir Path proj) {
        Path project = proj.resolve("my-app");
        ensureDir(project);
        // No db on disk. We still return the canonical path so callers can
        // produce a clean "not found at X" error message.
        Path got = DefaultIndexPath.forQueryRead(project, home);
        assertEquals(home.resolve("indexes").resolve(DefaultIndexPath.repoKeyOf(project)).resolve("index.db"), got);
    }

    @Test
    void repoName_handlesTrailingSlash(@TempDir Path home, @TempDir Path proj) {
        Path project = proj.resolve("my-app").resolve(""); // trailing-slash semantics
        ensureDir(project);
        Path got = DefaultIndexPath.forIndexWrite(project, home);
        assertEquals(home.resolve("indexes").resolve(DefaultIndexPath.repoKeyOf(project)).resolve("index.db"), got);
    }

    @Test
    void repoName_fallsBackForRootPath() {
        // "/" has no basename. We must produce something safe rather than NPE.
        Path got = DefaultIndexPath.forIndexWrite(Path.of("/"),
                Path.of("/tmp/test-home"));
        assertTrue(got.toString().matches("/tmp/test-home/indexes/default-project-[0-9a-f]{12}/index.db"));
    }

    @Test
    void sameBasenameDifferentCheckoutsGetDifferentKeys(@TempDir Path home,
                                                         @TempDir Path tmp) {
        Path first = tmp.resolve("one/my-app");
        Path second = tmp.resolve("two/my-app");
        ensureDir(first);
        ensureDir(second);

        assertNotEquals(DefaultIndexPath.forIndexWrite(first, home),
                DefaultIndexPath.forIndexWrite(second, home));
    }

    @Test
    void repoKeySanitizesNonAsciiAndSpacesWithoutChangingAllowedCharacters(
            @TempDir Path tmp) {
        Path project = tmp.resolve("my app-服务_1.0");
        ensureDir(project);

        assertTrue(DefaultIndexPath.repoKeyOf(project)
                .matches("my_app-___1\\.0-[0-9a-f]{12}"));
    }

    @Test
    void symlinkAndRealCheckoutShareKey(@TempDir Path home,
                                        @TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("my-app");
        ensureDir(project);
        Path link = tmp.resolve("linked-app");
        Files.createSymbolicLink(link, project);

        assertEquals(DefaultIndexPath.forIndexWrite(project, home),
                DefaultIndexPath.forIndexWrite(link, home));
    }

    @Test
    void resolveAnatomistHome_envVarOverrides() {
        Path override = Path.of("/tmp/custom-anatomist-home");
        assertEquals(override, DefaultIndexPath.resolveHome(override.toString(), "/Users/dummy"));
    }

    @Test
    void resolveAnatomistHome_defaultIsUnderUserHome() {
        assertEquals(Path.of("/Users/dummy/.anatomist"),
                DefaultIndexPath.resolveHome(null, "/Users/dummy"));
        assertEquals(Path.of("/Users/dummy/.anatomist"),
                DefaultIndexPath.resolveHome("", "/Users/dummy"));
    }

    private static void ensureDir(Path p) {
        try {
            Files.createDirectories(p);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
