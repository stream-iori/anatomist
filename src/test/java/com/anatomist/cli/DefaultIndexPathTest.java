package com.anatomist.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Pin the default storage policy:
 *
 *  <ul>
 *    <li>Index writes:   ~/.anatomist/&lt;repo-name&gt;/index.db (new default)</li>
 *    <li>Query reads:    ~/.anatomist/&lt;repo-name&gt;/index.db (new default)
 *        BUT falls back to the legacy in-project &lt;project&gt;/.anatomist/index.db
 *        when present, so existing users don't lose their data.</li>
 *    <li>An explicit path (--output or --index) always wins.</li>
 *    <li>The ANATOMIST_HOME env var can override the storage root for tests.</li>
 *  </ul>
 */
class DefaultIndexPathTest {

    @Test
    void defaultForWrite_isUnderAnatomistHomeWithRepoName(@TempDir Path home,
                                                          @TempDir Path proj) {
        Path project = proj.resolve("my-app");
        ensureDir(project);
        Path got = DefaultIndexPath.forIndexWrite(project, home);
        assertEquals(home.resolve("my-app").resolve("index.db"), got);
    }

    @Test
    void defaultForWrite_legacyInProjectDbWins_whenPresent(@TempDir Path home,
                                                            @TempDir Path proj) throws Exception {
        Path project = proj.resolve("my-app");
        ensureDir(project);
        // Pre-existing legacy database under <project>/.anatomist/index.db.
        Path legacy = project.resolve(".anatomist").resolve("index.db");
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, new byte[]{1});

        Path got = DefaultIndexPath.forIndexWrite(project, home);
        assertEquals(legacy, got,
                "should keep writing to the legacy location when one already exists");
    }

    @Test
    void defaultForRead_findsUnderAnatomistHomeWhenWriteWentThere(@TempDir Path home,
                                                                   @TempDir Path proj) throws Exception {
        Path project = proj.resolve("my-app");
        ensureDir(project);
        Path expected = home.resolve("my-app").resolve("index.db");
        Files.createDirectories(expected.getParent());
        Files.write(expected, new byte[]{1});

        Path got = DefaultIndexPath.forQueryRead(project, home);
        assertEquals(expected, got);
    }

    @Test
    void defaultForRead_findsLegacyInProjectDb(@TempDir Path home,
                                                @TempDir Path proj) throws Exception {
        Path project = proj.resolve("my-app");
        ensureDir(project);
        Path legacy = project.resolve(".anatomist").resolve("index.db");
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, new byte[]{1});

        Path got = DefaultIndexPath.forQueryRead(project, home);
        assertEquals(legacy, got);
    }

    @Test
    void defaultForRead_returnsExpectedPathEvenWhenMissing(@TempDir Path home,
                                                            @TempDir Path proj) {
        Path project = proj.resolve("my-app");
        ensureDir(project);
        // No db on disk. We still return the canonical path so callers can
        // produce a clean "not found at X" error message.
        Path got = DefaultIndexPath.forQueryRead(project, home);
        assertEquals(home.resolve("my-app").resolve("index.db"), got);
    }

    @Test
    void repoName_handlesTrailingSlash(@TempDir Path home, @TempDir Path proj) {
        Path project = proj.resolve("my-app").resolve(""); // trailing-slash semantics
        ensureDir(project);
        Path got = DefaultIndexPath.forIndexWrite(project, home);
        assertEquals(home.resolve("my-app").resolve("index.db"), got);
    }

    @Test
    void repoName_fallsBackForRootPath() {
        // "/" has no basename. We must produce something safe rather than NPE.
        Path got = DefaultIndexPath.forIndexWrite(Path.of("/"),
                Path.of("/tmp/test-home"));
        assertEquals(Path.of("/tmp/test-home/default-project/index.db"), got);
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
