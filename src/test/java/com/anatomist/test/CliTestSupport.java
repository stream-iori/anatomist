package com.anatomist.test;

import com.anatomist.cli.IndexCommand;
import org.junit.jupiter.api.Assertions;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public final class CliTestSupport {
    private CliTestSupport() {
    }

    public record RunResult(int exitCode, String stdout, String stderr) {
    }

    @FunctionalInterface
    public interface ThrowingIntSupplier {
        int get() throws Exception;
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static RunResult capture(ThrowingIntSupplier action) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int rc = action.get();
            return new RunResult(
                    rc,
                    out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    public static RunResult runIndex(Path project, String... args) throws Exception {
        List<String> allArgs = new ArrayList<>();
        allArgs.add(project.toString());
        allArgs.addAll(Arrays.asList(args));
        return capture(() -> new CommandLine(new IndexCommand()).execute(allArgs.toArray(String[]::new)));
    }

    public static void assertIndexOk(Path project, String... args) throws Exception {
        RunResult result = runIndex(project, args);
        Assertions.assertEquals(0, result.exitCode(),
                "index failed\nstdout:\n" + result.stdout() + "\nstderr:\n" + result.stderr());
    }

    public static Path repoRoot() {
        return Path.of(System.getProperty("user.dir"));
    }

    public static Path miniSpringFixture() {
        Path fixture = repoRoot().resolve("fixtures/mini-spring-shop");
        Assertions.assertTrue(Files.isDirectory(fixture), "fixture missing: " + fixture);
        return fixture;
    }

    public static Path copyMiniSpringFixture(Path tmp) throws Exception {
        Path dst = tmp.resolve("project");
        copyDir(miniSpringFixture(), dst);
        return dst;
    }

    public static String miniSpringProjectSource(Path project) {
        return String.join(File.pathSeparator,
                project.resolve("api/src/main/java").toString(),
                project.resolve("domain/src/main/java").toString(),
                project.resolve("service/src/main/java").toString());
    }

    public static Path createSimpleMavenProject(Path tmp, boolean includeTests) throws Exception {
        Path project = tmp.resolve("proj");
        Path mainSource = project.resolve("src/main/java/p");
        Files.createDirectories(mainSource);
        Files.writeString(project.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>p</groupId>
                  <artifactId>test-project</artifactId>
                  <version>1.0-SNAPSHOT</version>
                </project>
                """, StandardCharsets.UTF_8);
        Files.writeString(mainSource.resolve("A.java"),
                "package p; class A { void run() {} }\n", StandardCharsets.UTF_8);
        if (includeTests) {
            Path testSource = project.resolve("src/test/java/p");
            Files.createDirectories(testSource);
            Files.writeString(testSource.resolve("ATest.java"),
                    "package p; class ATest { void before() {} }\n", StandardCharsets.UTF_8);
        }
        return project;
    }

    public static void copyDir(Path src, Path dst) throws Exception {
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
