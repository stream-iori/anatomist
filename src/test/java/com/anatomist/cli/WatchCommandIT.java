package com.anatomist.cli;

import com.anatomist.core.IndexOutcome;
import com.anatomist.incremental.IncrementalParseException;
import com.anatomist.store.FileCacheService;
import com.anatomist.test.CliTestSupport;
import com.anatomist.test.CliTestSupport.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    private RunResult runWatchWithRunner(Path project,
                                         Path source,
                                         WatchCommand.IndexCommandRunner runner,
                                         int maxIterations,
                                         boolean failFast) throws Exception {
        WatchCommand cmd = new WatchCommand();
        List<String> args = new ArrayList<>(List.of(
                project.toString(),
                "--project-source", project.resolve("src/main/java").toString(),
                "--no-classpath", "--output", project.resolve("watch-test.db").toString(),
                "--auto-index", "--debounce-ms", "100",
                "--max-iterations", String.valueOf(maxIterations)));
        if (failFast) args.add("--fail-fast");
        new CommandLine(cmd).parseArgs(args.toArray(new String[0]));
        cmd.setIndexCommandRunnerForTest(runner);
        CountDownLatch ready = new CountDownLatch(1);
        cmd.setReadyListenerForTest(ready::countDown);

        AtomicInteger rc = new AtomicInteger(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        return CliTestSupport.capture(() -> {
            Thread thread = new Thread(() -> {
                try {
                    rc.set(cmd.call());
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            thread.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS), "watch did not become ready");
            Files.writeString(source, Files.readString(source) + "\n// trigger watch\n");
            thread.join(10_000);
            assertFalse(thread.isAlive(), "watch retry test did not finish");
            if (failure.get() != null) throw new AssertionError(failure.get());
            return rc.get();
        });
    }

    private static IncrementalParseException transientParseFailure(Path project, Path source) {
        String relative = project.relativize(source).toString();
        return new IncrementalParseException(
                List.of(relative), Map.of(relative, List.of("Found throws, expected a declaration")));
    }

    private static String cachedHash(Path db, String sourceFile) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.prepareStatement(
                     "SELECT hash FROM file_cache WHERE source_file=?")) {
            statement.setString(1, sourceFile);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static long occurrences(String text, String needle) {
        return text.lines().filter(line -> line.contains(needle)).count();
    }

    @Test
    void transientParseFailureRetriesWithoutAnotherWatchEvent(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false).toRealPath();
        Path source = project.resolve("src/main/java/p/A.java");
        AtomicInteger attempts = new AtomicInteger();

        RunResult result = runWatchWithRunner(project, source, command ->
                        attempts.getAndIncrement() == 0
                                ? IndexOutcome.failure(transientParseFailure(project, source))
                                : IndexOutcome.success(0),
                2, false);

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals(2, attempts.get(), "one event should drive the timed retry");
        assertTrue(result.stderr().contains("retry 1/3"), result.stderr());
        assertEquals(1, occurrences(result.stdout(), "[MODIFY]"),
                "timed retries must not print duplicate filesystem events");
    }

    @Test
    void persistentParseFailureStopsAfterThreeRetries(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false).toRealPath();
        Path source = project.resolve("src/main/java/p/A.java");
        AtomicInteger attempts = new AtomicInteger();

        RunResult result = runWatchWithRunner(project, source, command -> {
            attempts.incrementAndGet();
            return IndexOutcome.failure(transientParseFailure(project, source));
        }, 4, false);

        assertEquals(1, result.exitCode());
        assertEquals(4, attempts.get(), "initial attempt plus three retries");
        assertTrue(result.stderr().contains("remains unparsable after 3 retries"), result.stderr());
        assertFalse(result.stderr().contains("at com.anatomist"), result.stderr());
    }

    @Test
    void failFastDoesNotRetryParseFailure(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false).toRealPath();
        Path source = project.resolve("src/main/java/p/A.java");
        AtomicInteger attempts = new AtomicInteger();

        RunResult result = runWatchWithRunner(project, source, command -> {
            attempts.incrementAndGet();
            return IndexOutcome.failure(transientParseFailure(project, source));
        }, 4, true);

        assertEquals(1, result.exitCode());
        assertEquals(1, attempts.get());
        assertTrue(result.stderr().contains("--fail-fast"), result.stderr());
        assertFalse(result.stderr().contains("retry 1/3"), result.stderr());
    }

    @Test
    void nonParseFailureDoesNotEnterTimedRetry(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false).toRealPath();
        Path source = project.resolve("src/main/java/p/A.java");
        AtomicInteger attempts = new AtomicInteger();

        RunResult result = runWatchWithRunner(project, source, command -> {
            attempts.incrementAndGet();
            return IndexOutcome.failure(new IllegalStateException("database unavailable"));
        }, 1, false);

        assertEquals(1, result.exitCode());
        assertEquals(1, attempts.get());
        assertFalse(result.stderr().contains("retry 1/3"), result.stderr());
        assertTrue(result.stderr().contains("database unavailable"), result.stderr());
    }

    @Test
    void invalidThenValidSaveRecoversAndKeepsLastGoodCache(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false).toRealPath();
        Path source = project.resolve("src/main/java/p/A.java");
        Path db = tmp.resolve("index.db");
        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--java-version", "17", "--output", db.toString());
        String relative = project.relativize(source).toString();
        String beforeHash = cachedHash(db, relative);

        WatchCommand cmd = new WatchCommand();
        new CommandLine(cmd).parseArgs(
                project.toString(), "--project-source", project.resolve("src/main/java").toString(),
                "--no-classpath", "--output", db.toString(),
                "--java-version", "17", "--auto-index", "--debounce-ms", "100",
                "--idle-timeout-ms", "5000");
        AtomicInteger rc = new AtomicInteger(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch parseFailed = new CountDownLatch(1);
        cmd.setReadyListenerForTest(ready::countDown);
        cmd.setParseFailureListenerForTest(parseFailure -> parseFailed.countDown());
        RunResult result = CliTestSupport.capture(() -> {
            Thread thread = new Thread(() -> {
                try {
                    rc.set(cmd.call());
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            thread.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS), "watch did not become ready");
            Files.writeString(source, "package p; class A { void run() {} throws Exception }");
            assertTrue(parseFailed.await(5, TimeUnit.SECONDS),
                    "watch did not observe the invalid source snapshot");
            assertEquals(beforeHash, cachedHash(db, relative),
                    "unparsable source must leave the last committed cache intact");
            Files.writeString(source, "package p; class A { void recovered() {} }\n");
            thread.join(10_000);
            assertFalse(thread.isAlive(), "watch did not recover after valid save");
            if (failure.get() != null) throw new AssertionError(failure.get());
            return rc.get();
        });

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stderr().contains("temporarily unparsable"),
                "stdout:\n" + result.stdout() + "\nstderr:\n" + result.stderr());
        assertFalse(result.stderr().contains("at com.anatomist"), result.stderr());
        assertEquals(FileCacheService.sha256(source), cachedHash(db, relative));
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
    void watchAutoIndexCanReportPhaseTimings(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertMiniSpringIndexOk(project, db);

        Path source = project.resolve(
                "service/src/main/java/com/example/shop/service/OrderService.java");
        RunResult result = runWatchAndMutate(project, db, true, () ->
                Files.writeString(source, Files.readString(source) + "\n// timings\n"),
                "--auto-index", "--timings");

        assertTrue(result.stdout().contains("Timings (ms):"), result.stdout());
        assertTrue(result.stdout().contains("change_detection="), result.stdout());
        assertTrue(result.stdout().contains("parse_extract="), result.stdout());
        assertTrue(result.stdout().contains("total="), result.stdout());
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
    void watchPomCommentRedetectsMavenClasspathWithoutFullIndex(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Path db = tmp.resolve("index.db");
        CliTestSupport.assertIndexOk(project,
                "--java-version", "17",
                "--output", db.toString());

        Path pom = project.resolve("pom.xml");
        RunResult result = runWatchAndMutate(project, db, false, false, () -> {
            String orig = Files.readString(pom);
            Files.writeString(pom, orig + "\n<!-- touched -->\n");
                }, "--auto-index", "--extensions", ".java,.xml", "--java-version", "17");

        assertTrue(result.stdout().contains("Build environment unchanged"), result.stdout());
        assertFalse(result.stdout().contains("Full re-index"),
                "comment-only pom.xml change must not trigger full re-index; got:\n" + result.stdout());
        assertTrue(result.stderr().contains("Detecting classpath via Maven"),
                "build file changes should refresh Maven classpath; stderr:\n" + result.stderr());
    }

    @Test
    void testWatchPomChangeWithStableNoClasspathEnvironmentStaysIncremental(@TempDir Path tmp) throws Exception {
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

        assertTrue(stdout.contains("Build environment unchanged"), stdout);
        assertFalse(stdout.contains("Full re-index"), stdout);
    }

    @Test
    void watchBuildArtifactChangeTriggersFullReindex(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Path jar = tmp.resolve("dependency.jar");
        Files.writeString(jar, "before");
        Path db = tmp.resolve("index.db");
        CliTestSupport.assertIndexOk(project,
                "--classpath", jar.toString(), "--java-version", "17",
                "--output", db.toString());

        Path pom = project.resolve("pom.xml");
        RunResult result = runWatchAndMutate(project, db, false, false, () -> {
            Files.writeString(jar, "changed artifact bytes");
            Files.writeString(pom, Files.readString(pom) + "\n<!-- dependency refreshed -->\n");
        }, "--auto-index", "--classpath", jar.toString(), "--java-version", "17");

        assertTrue(result.stdout().contains("Build environment changed"), result.stdout());
        assertTrue(result.stdout().contains("Full re-index"), result.stdout());
    }

    @Test
    void successfulEnvironmentFullRestoresCandidateFastPath(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false).toRealPath();
        Path source = project.resolve("src/main/java/p/A.java");
        Path pom = project.resolve("pom.xml");
        Path jar = tmp.resolve("dependency.jar");
        Files.writeString(jar, "before");
        Path db = tmp.resolve("index.db");
        CliTestSupport.assertIndexOk(project,
                "--classpath", jar.toString(), "--java-version", "17",
                "--output", db.toString());

        WatchCommand command = new WatchCommand();
        new CommandLine(command).parseArgs(project.toString(), "--auto-index",
                "--classpath", jar.toString(), "--java-version", "17",
                "--output", db.toString(), "--debounce-ms", "100",
                "--max-iterations", "2");
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Boolean> secondFastPath = new AtomicReference<>(false);
        command.setIndexCommandRunnerForTest(index -> {
            int attempt = attempts.getAndIncrement();
            if (attempt == 0) {
                try {
                    Files.writeString(source, Files.readString(source) + "\n// after full\n");
                } catch (java.io.IOException ex) {
                    throw new RuntimeException(ex);
                }
            } else {
                secondFastPath.set(index.usesCandidateFastPathForTest());
            }
            return IndexOutcome.success(0);
        });
        CountDownLatch ready = new CountDownLatch(1);
        command.setReadyListenerForTest(ready::countDown);

        AtomicInteger rc = new AtomicInteger(-1);
        Thread thread = new Thread(() -> rc.set(command.call()));
        thread.start();
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        Files.writeString(jar, "changed artifact bytes");
        Files.writeString(pom, Files.readString(pom) + "\n<!-- changed -->\n");
        thread.join(20_000);

        assertFalse(thread.isAlive());
        assertEquals(0, rc.get());
        assertEquals(2, attempts.get());
        assertTrue(secondFastPath.get(), "second source edit should use candidate fast path");
    }

    @Test
    void backgroundFullContinuesCollectingEventsAndReplaysThem(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false).toRealPath();
        Path source = project.resolve("src/main/java/p/A.java");
        Path pom = project.resolve("pom.xml");
        Path jar = tmp.resolve("dependency.jar");
        Files.writeString(jar, "before");
        Path db = tmp.resolve("index.db");
        CliTestSupport.assertIndexOk(project,
                "--classpath", jar.toString(), "--java-version", "17", "--output", db.toString());

        WatchCommand command = new WatchCommand();
        new CommandLine(command).parseArgs(project.toString(), "--auto-index",
                "--classpath", jar.toString(), "--java-version", "17", "--output", db.toString(),
                "--debounce-ms", "100", "--max-iterations", "2", "--full-policy", "background");
        CountDownLatch fullStarted = new CountDownLatch(1);
        CountDownLatch releaseFull = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Boolean> replayWasIncremental = new AtomicReference<>(false);
        command.setIndexCommandRunnerForTest(index -> {
            if (attempts.getAndIncrement() == 0) {
                fullStarted.countDown();
                try {
                    assertTrue(releaseFull.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ex);
                }
            } else {
                replayWasIncremental.set(index.usesCandidateFastPathForTest());
            }
            return IndexOutcome.success(0);
        });
        CountDownLatch ready = new CountDownLatch(1);
        command.setReadyListenerForTest(ready::countDown);
        AtomicInteger rc = new AtomicInteger(-1);
        Thread watch = new Thread(() -> rc.set(command.call()));
        watch.start();
        assertTrue(ready.await(5, TimeUnit.SECONDS));

        Files.writeString(jar, "changed artifact bytes");
        Files.writeString(pom, Files.readString(pom) + "\n<!-- changed -->\n");
        assertTrue(fullStarted.await(5, TimeUnit.SECONDS), "background full did not start");
        Files.writeString(source, Files.readString(source) + "\n// changed while full runs\n");
        releaseFull.countDown();
        watch.join(10_000);

        assertFalse(watch.isAlive(), "watch should replay events after background full");
        assertEquals(0, rc.get());
        assertEquals(2, attempts.get(), "one full plus one replay incremental expected");
        assertTrue(replayWasIncremental.get(), "replayed source edit should use candidate fast path");
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
