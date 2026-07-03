package com.anatomist.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class WatchCommandIT {

    private Path setupFixtureCopy(Path tmp) throws Exception {
        Path repoRoot = Path.of(System.getProperty("user.dir"));
        Path src = repoRoot.resolve("fixtures/mini-spring-shop");
        Path dst = tmp.resolve("project");
        copyDir(src, dst);
        return dst;
    }

    private String runWatchAndMutate(Path project, Path db, String[] extraArgs, Runnable mutation) throws Exception {
        String projectSource = String.join(File.pathSeparator,
                project.resolve("api/src/main/java").toString(),
                project.resolve("domain/src/main/java").toString(),
                project.resolve("service/src/main/java").toString());

        ByteArrayOutputStream stdoutCap = new ByteArrayOutputStream();
        PrintStream original = System.out;

        WatchCommand cmd = new WatchCommand();
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add(project.toString());
        args.add("--project-source"); args.add(projectSource);
        args.add("--no-classpath");
        args.add("--output"); args.add(db.toString());
        args.add("--debounce-ms"); args.add("200");
        args.add("--idle-timeout-ms"); args.add("3000");
        for (String a : extraArgs) args.add(a);
        new CommandLine(cmd).parseArgs(args.toArray(new String[0]));

        AtomicInteger rc = new AtomicInteger(-1);
        Thread t;
        try {
            System.setOut(new PrintStream(stdoutCap, true, StandardCharsets.UTF_8));
            t = new Thread(() -> rc.set(cmd.call()));
            t.start();
            // Give the watcher time to register listeners
            Thread.sleep(600);
            mutation.run();
            t.join(15000);
        } finally {
            System.setOut(original);
        }
        return stdoutCap.toString(StandardCharsets.UTF_8);
    }

    private int runFullIndex(Path project, Path db) {
        String projectSource = String.join(File.pathSeparator,
                project.resolve("api/src/main/java").toString(),
                project.resolve("domain/src/main/java").toString(),
                project.resolve("service/src/main/java").toString());
        IndexCommand cmd = new IndexCommand();
        new CommandLine(cmd).parseArgs(
                project.toString(),
                "--project-source", projectSource,
                "--no-classpath",
                "--output", db.toString()
        );
        return cmd.call();
    }

    @Test
    void testWatchDetectsModification(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertEquals(0, runFullIndex(project, db));

        Path osvc = project.resolve("service/src/main/java/com/example/shop/service/OrderService.java");
        String stdout = runWatchAndMutate(project, db, new String[]{}, () -> {
            try {
                String orig = Files.readString(osvc);
                Files.writeString(osvc, orig + "\n// touched\n");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        assertTrue(stdout.contains("[MODIFY]"),
                "stdout should contain [MODIFY] line; got:\n" + stdout);
        assertTrue(stdout.contains("OrderService.java"),
                "stdout should reference OrderService.java; got:\n" + stdout);
    }

    @Test
    void testWatchAutoIndex(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertEquals(0, runFullIndex(project, db));

        Path osvc = project.resolve("service/src/main/java/com/example/shop/service/OrderService.java");
        String stdout = runWatchAndMutate(project, db, new String[]{"--auto-index"}, () -> {
            try {
                String orig = Files.readString(osvc);
                Files.writeString(osvc, orig + "\n// touched\n");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

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
    void testWatchPomChangeTriggersFullReindex(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertEquals(0, runFullIndex(project, db));

        Path pom = project.resolve("pom.xml");
        if (!Files.exists(pom)) {
            Files.writeString(pom, "<project/>");
        }
        String stdout = runWatchAndMutate(project, db, new String[]{"--auto-index", "--extensions", ".java,.xml"}, () -> {
            try {
                String orig = Files.readString(pom);
                Files.writeString(pom, orig + "\n<!-- touched -->\n");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        assertTrue(stdout.contains("full re-index") || stdout.contains("Full re-index")
                || stdout.contains("Indexed"),
                "pom.xml change should trigger full re-index; got:\n" + stdout);
    }

    @Test
    void testWatchExtensionsFilter(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertEquals(0, runFullIndex(project, db));

        Path xml = project.resolve("service/src/main/java/com/example/shop/service/dummy.xml");
        Files.writeString(xml, "<a/>");

        String stdout = runWatchAndMutate(project, db, new String[]{"--extensions", ".java"}, () -> {
            try {
                Files.writeString(xml, "<a/><b/>");
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        assertFalse(stdout.contains("dummy.xml"),
                "with --extensions .java, dummy.xml events should be filtered; got:\n" + stdout);
    }

    private static void copyDir(Path src, Path dst) throws Exception {
        try (Stream<Path> stream = Files.walk(src)) {
            stream.forEach(p -> {
                try {
                    Path rel = src.relativize(p);
                    Path target = dst.resolve(rel.toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
