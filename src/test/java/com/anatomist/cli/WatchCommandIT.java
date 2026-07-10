package com.anatomist.cli;

import com.anatomist.test.CliTestSupport;
import com.anatomist.test.CliTestSupport.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WatchCommandIT {

    private Path setupFixtureCopy(Path tmp) throws Exception {
        return CliTestSupport.copyMiniSpringFixture(tmp);
    }

    private RunResult runWatchAndMutate(Path project,
                                        Path db,
                                        boolean includeProjectSource,
                                        CliTestSupport.ThrowingRunnable mutation,
                                        String... extraArgs) throws Exception {
        return runWatchAndMutate(project, db, includeProjectSource, true, mutation, extraArgs);
    }

    private RunResult runWatchAndMutate(Path project,
                                        Path db,
                                        boolean includeProjectSource,
                                        boolean noClasspath,
                                        CliTestSupport.ThrowingRunnable mutation,
                                        String... extraArgs) throws Exception {
        WatchCommand cmd = new WatchCommand();
        List<String> args = new ArrayList<>();
        args.add(project.toString());
        if (includeProjectSource) {
            args.add("--project-source");
            args.add(CliTestSupport.miniSpringProjectSource(project));
        }
        if (noClasspath) args.add("--no-classpath");
        args.add("--output"); args.add(db.toString());
        args.add("--debounce-ms"); args.add("200");
        args.add("--idle-timeout-ms"); args.add("3000");
        for (String a : extraArgs) args.add(a);
        new CommandLine(cmd).parseArgs(args.toArray(new String[0]));

        AtomicInteger rc = new AtomicInteger(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        RunResult result = CliTestSupport.capture(() -> {
            Thread t = new Thread(() -> {
                try {
                    rc.set(cmd.call());
                } catch (Throwable t1) {
                    failure.set(t1);
                }
            });
            t.start();
            Thread.sleep(600);
            mutation.run();
            t.join(15000);
            assertFalse(t.isAlive(), "watch command did not exit after mutation");
            if (failure.get() != null) {
                throw new AssertionError("watch command failed", failure.get());
            }
            return rc.get();
        });
        assertEquals(0, result.exitCode(),
                "watch failed\nstdout:\n" + result.stdout() + "\nstderr:\n" + result.stderr());
        return result;
    }

    private void assertMiniSpringIndexOk(Path project, Path db) throws Exception {
        CliTestSupport.assertIndexOk(project,
                "--project-source", CliTestSupport.miniSpringProjectSource(project),
                "--no-classpath",
                "--output", db.toString());
    }

    private void assertIndexWithTestsOk(Path project, Path db) throws Exception {
        CliTestSupport.assertIndexOk(project,
                "--include-tests",
                "--no-classpath",
                "--output", db.toString());
    }

    @Test
    void testWatchDetectsModification(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertMiniSpringIndexOk(project, db);

        Path osvc = project.resolve("service/src/main/java/com/example/shop/service/OrderService.java");
        RunResult result = runWatchAndMutate(project, db, true, () -> {
            String orig = Files.readString(osvc);
            Files.writeString(osvc, orig + "\n// touched\n");
        });
        String stdout = result.stdout();

        assertTrue(stdout.contains("[MODIFY]"),
                "stdout should contain [MODIFY] line; got:\n" + stdout);
        assertTrue(stdout.contains("OrderService.java"),
                "stdout should reference OrderService.java; got:\n" + stdout);
    }

    @Test
    void testWatchAutoIndex(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertMiniSpringIndexOk(project, db);

        Path osvc = project.resolve("service/src/main/java/com/example/shop/service/OrderService.java");
        RunResult result = runWatchAndMutate(project, db, true, () -> {
            String orig = Files.readString(osvc);
            Files.writeString(osvc, orig + "\n// touched\n");
        }, "--auto-index");
        String stdout = result.stdout();

        assertTrue(stdout.contains("[MODIFY]") || stdout.contains("Incremental"),
                "stdout should reflect change event; got:\n" + stdout);
        assertTrue(stdout.contains("Indexed") || stdout.contains("incremental"),
                "stdout should reflect incremental index result; got:\n" + stdout);
        assertTrue(stdout.contains("Written nodes:"),
                "stdout should report written graph rows; got:\n" + stdout);
        assertFalse(stdout.contains("New nodes:"),
                "stdout should not use misleading New nodes label; got:\n" + stdout);
    }

    @Test
    void failedAutoIndexRetainsPendingChangesAndReturnsNonZero(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertMiniSpringIndexOk(project, db);

        WatchCommand cmd = new WatchCommand();
        new CommandLine(cmd).parseArgs(
                project.toString(),
                "--project-source", CliTestSupport.miniSpringProjectSource(project),
                "--no-classpath", "--output", db.toString(),
                "--auto-index", "--strict-health",
                "--debounce-ms", "100", "--max-iterations", "1");

        AtomicInteger rc = new AtomicInteger(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        RunResult result = CliTestSupport.capture(() -> {
            Thread thread = new Thread(() -> {
                try {
                    rc.set(cmd.call());
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            thread.start();
            Thread.sleep(500);
            // Force the auto-index attempt itself to fail. The fixture is now
            // healthy, so --strict-health is no longer an accidental failure
            // trigger for this retry-behaviour test.
            Files.writeString(db, "not a sqlite database", StandardCharsets.UTF_8);
            Path service = project.resolve(
                    "service/src/main/java/com/example/shop/service/OrderService.java");
            Files.writeString(service, Files.readString(service) + "\n// strict health retry\n");
            thread.join(15_000);
            assertFalse(thread.isAlive());
            if (failure.get() != null) throw new AssertionError(failure.get());
            return rc.get();
        });

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("retaining 1 pending change"), result.stderr());
    }

    @Test
    void watchAutoIndexPassesIncludeTests(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, true);
        Path testFile = project.resolve("src/test/java/p/ATest.java");
        Path db = tmp.resolve("index.db");
        assertIndexWithTestsOk(project, db);

        RunResult result = runWatchAndMutate(project, db, false, () -> Files.writeString(testFile,
                "package p; class ATest { void before() {} void after() {} }\n",
                StandardCharsets.UTF_8), "--include-tests", "--auto-index");
        String stdout = result.stdout();

        assertTrue(stdout.contains("[MODIFY]"), "stdout should contain modify event; got:\n" + stdout);
        assertTrue(stdout.contains("ATest.java"), "stdout should reference test source; got:\n" + stdout);
        assertTrue(stdout.contains("Changed files: 1"),
                "watch should pass --include-tests through to index; got:\n" + stdout);
        assertTrue(stdout.contains("Written nodes:"),
                "stdout should include incremental write counts; got:\n" + stdout);
    }

    @Test
    void watchAutoIndexReusesCachedMavenClasspath(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, true);
        Path testFile = project.resolve("src/test/java/p/ATest.java");
        Path db = tmp.resolve("index.db");
        CliTestSupport.assertIndexOk(project,
                "--include-tests",
                "--java-version", "17",
                "--output", db.toString());

        RunResult result = runWatchAndMutate(project, db, false, false,
                () -> Files.writeString(testFile,
                        "package p; class ATest { void before() {} void after() {} }\n",
                        StandardCharsets.UTF_8),
                "--include-tests", "--auto-index");

        assertTrue(result.stdout().contains("Changed files: 1"),
                "watch should incrementally index changed test source; got:\n" + result.stdout());
        assertFalse(result.stderr().contains("Detecting classpath via Maven"),
                "watch incremental should reuse cached Maven classpath; stderr:\n" + result.stderr());
        assertTrue(result.stderr().contains("Parsing with Java 17"),
                "watch incremental should reuse cached java_version; stderr:\n" + result.stderr());
    }

    @Test
    void watchPomChangeStillRedetectsMavenClasspath(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Path db = tmp.resolve("index.db");
        CliTestSupport.assertIndexOk(project,
                "--java-version", "17",
                "--output", db.toString());

        Path pom = project.resolve("pom.xml");
        RunResult result = runWatchAndMutate(project, db, false, false, () -> {
            String orig = Files.readString(pom);
            Files.writeString(pom, orig + "\n<!-- touched -->\n");
        }, "--auto-index", "--extensions", ".java,.xml");

        assertTrue(result.stdout().contains("Full re-index"),
                "pom.xml change should trigger full re-index; got:\n" + result.stdout());
        assertTrue(result.stderr().contains("Detecting classpath via Maven"),
                "build file changes should refresh Maven classpath; stderr:\n" + result.stderr());
    }

    @Test
    void testWatchPomChangeTriggersFullReindex(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertMiniSpringIndexOk(project, db);

        Path pom = project.resolve("pom.xml");
        if (!Files.exists(pom)) {
            Files.writeString(pom, "<project/>");
        }
        RunResult result = runWatchAndMutate(project, db, true, () -> {
            String orig = Files.readString(pom);
            Files.writeString(pom, orig + "\n<!-- touched -->\n");
        }, "--auto-index", "--extensions", ".java,.xml");
        String stdout = result.stdout();

        assertTrue(stdout.contains("full re-index") || stdout.contains("Full re-index")
                || stdout.contains("Indexed"),
                "pom.xml change should trigger full re-index; got:\n" + stdout);
    }

    @Test
    void testWatchExtensionsFilter(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertMiniSpringIndexOk(project, db);

        Path xml = project.resolve("service/src/main/java/com/example/shop/service/dummy.xml");
        Files.writeString(xml, "<a/>");

        RunResult result = runWatchAndMutate(project, db, true,
                () -> Files.writeString(xml, "<a/><b/>"),
                "--extensions", ".java");
        String stdout = result.stdout();

        assertFalse(stdout.contains("dummy.xml"),
                "with --extensions .java, dummy.xml events should be filtered; got:\n" + stdout);
    }

}
