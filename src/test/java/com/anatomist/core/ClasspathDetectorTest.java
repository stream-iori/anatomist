package com.anatomist.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

class ClasspathDetectorTest {

    private final PrintStream originalErr = System.err;
    private ByteArrayOutputStream errCapture;

    @BeforeEach
    void captureErr() {
        errCapture = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errCapture, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreErr() {
        System.setErr(originalErr);
    }

    @Test
    void detect_returnsEmptyAndWarnsWhenMvnUnavailable(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");

        ClasspathDetector det = new ClasspathDetector() {
            @Override
            protected int runMvn(Path workingDir, List<String> args) throws IOException {
                throw new IOException("mvn not found on PATH");
            }
        };

        List<String> cp = det.detect(tmp);
        assertTrue(cp.isEmpty(), "expected empty classpath; got " + cp);

        String err = errCapture.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("WARN"), "expected WARN in stderr; got: " + err);
        assertTrue(err.contains("mvn"), "expected 'mvn' in stderr; got: " + err);
    }

    @Test
    void detect_returnsEmptyForNonMavenProject(@TempDir Path tmp) {
        ClasspathDetector det = new ClasspathDetector() {
            @Override
            protected int runMvn(Path workingDir, List<String> args) {
                throw new AssertionError("runMvn must not be invoked for non-Maven project");
            }
        };
        assertTrue(det.detect(tmp).isEmpty());
    }

    @Test
    void detectSourcePaths_returnsSrcMainJavaForMavenProject(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Path src = Files.createDirectories(tmp.resolve("src/main/java"));

        List<Path> paths = new ClasspathDetector().detectSourcePaths(tmp);
        assertEquals(List.of(src), paths);
    }

    @Test
    void detectSourcePaths_collectsAllModuleRootsForMultiModuleReactor(@TempDir Path tmp) throws Exception {
        // Root reactor pom with no src/main/java of its own.
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Path a = Files.createDirectories(tmp.resolve("app/api/src/main/java"));
        Path b = Files.createDirectories(tmp.resolve("app/core/service/src/main/java"));
        // Noise that must be excluded.
        Files.createDirectories(tmp.resolve("app/api/target/classes/src/main/java"));
        Files.createDirectories(tmp.resolve("app/api/src/test/java"));

        List<Path> paths = new ClasspathDetector().detectSourcePaths(tmp);
        assertEquals(List.of(a, b), paths);
    }

    @Test
    void detectSourcePaths_includesGeneratedSourcesRoots(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Path main = Files.createDirectories(tmp.resolve("app/core/src/main/java"));
        Path generated = Files.createDirectories(tmp.resolve("app/core/target/generated-sources/annotations"));
        Path pkg = Files.createDirectories(generated.resolve("com/example"));
        Files.writeString(pkg.resolve("GeneratedType.java"), "package com.example; class GeneratedType {}");

        List<Path> paths = new ClasspathDetector().detectSourcePaths(tmp);
        assertTrue(paths.contains(main), "expected main source root; got " + paths);
        assertTrue(paths.contains(generated), "expected generated source root; got " + paths);
    }

    @Test
    void detectBuildOutputClasspath_collectsTargetClasses(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("app/core/target/classes"));
        Path testClasses = Files.createDirectories(tmp.resolve("app/core/target/test-classes"));

        List<Path> paths = new ClasspathDetector().detectBuildOutputClasspath(tmp);
        assertTrue(paths.contains(classes), "expected target/classes; got " + paths);
        assertTrue(paths.contains(testClasses), "expected target/test-classes; got " + paths);
    }

    @Test
    void detectSourcePaths_includesTestRootsWhenRequested(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Path main = Files.createDirectories(tmp.resolve("app/core/src/main/java"));
        Path test = Files.createDirectories(tmp.resolve("app/core/src/test/java"));
        // A test-only module (no src/main/java) — must still be picked up.
        Path testOnly = Files.createDirectories(tmp.resolve("app/test/src/test/java"));

        List<Path> withTests = new ClasspathDetector().detectSourcePaths(tmp, true);
        assertTrue(withTests.contains(main), "expected main root; got " + withTests);
        assertTrue(withTests.contains(test), "expected test root; got " + withTests);
        assertTrue(withTests.contains(testOnly), "expected test-only module root; got " + withTests);

        List<Path> withoutTests = new ClasspathDetector().detectSourcePaths(tmp, false);
        assertEquals(List.of(main), withoutTests);
    }

    @Test
    void detectSourcePaths_singleModuleIncludesTestRoot(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Path main = Files.createDirectories(tmp.resolve("src/main/java"));
        Path test = Files.createDirectories(tmp.resolve("src/test/java"));

        assertEquals(List.of(main), new ClasspathDetector().detectSourcePaths(tmp, false));
        assertEquals(List.of(main, test), new ClasspathDetector().detectSourcePaths(tmp, true));
    }

    @Test
    void detect_unionsGoodModulesWhenOneModuleFails(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Path good = Files.createDirectories(tmp.resolve("app/core"));
        Path bad = Files.createDirectories(tmp.resolve("app/test"));

        ClasspathDetector det = new ClasspathDetector() {
            @Override
            protected int runMvn(Path workingDir, List<String> args) throws IOException {
                // Reactor with -fae: the good module writes its file; the bad one
                // (unresolvable system dep) writes nothing, and mvn exits non-zero.
                String rel = extractOutputFile(args).toString();
                Files.writeString(good.resolve(rel),
                        "/lib/shared.jar" + File.pathSeparator + "/lib/core.jar");
                return 1; // overall reactor failure
            }
        };

        List<String> cp = det.detect(tmp);
        // Despite exit 1, the good module's classpath survives.
        assertEquals(List.of("/lib/shared.jar", "/lib/core.jar"), cp);
        String err = errCapture.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("WARN"), "expected WARN about partial classpath; got: " + err);
    }

    @Test
    void detect_parsesClasspathFromMockedMvnOutput(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        String mockOutput = "/lib/a.jar" + File.pathSeparator + "/lib/b.jar";

        ClasspathDetector det = new ClasspathDetector() {
            @Override
            protected int runMvn(Path workingDir, List<String> args) throws IOException {
                // Mimic Maven writing the relative outputFile into the module basedir.
                Path out = workingDir.resolve(extractOutputFile(args));
                Files.writeString(out, mockOutput);
                return 0;
            }
        };

        List<String> cp = det.detect(tmp);
        assertEquals(List.of("/lib/a.jar", "/lib/b.jar"), cp);
    }

    @Test
    void detect_unionsAndDedupesAcrossReactorModules(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Path modA = Files.createDirectories(tmp.resolve("app/core"));
        Path modB = Files.createDirectories(tmp.resolve("app/web"));

        ClasspathDetector det = new ClasspathDetector() {
            @Override
            protected int runMvn(Path workingDir, List<String> args) throws IOException {
                // Simulate the reactor: each module writes its own relative file.
                String rel = extractOutputFile(args).toString();
                Files.writeString(tmp.resolve(rel),
                        "/lib/shared.jar" + File.pathSeparator + "/lib/a.jar");
                Files.writeString(modA.resolve(rel),
                        "/lib/shared.jar" + File.pathSeparator + "/lib/core.jar");
                Files.writeString(modB.resolve(rel),
                        "/lib/shared.jar" + File.pathSeparator + "/lib/web.jar");
                return 0;
            }
        };

        List<String> cp = det.detect(tmp);
        // shared.jar appears once; union covers every module; no leftover files.
        assertEquals(List.of("/lib/shared.jar", "/lib/a.jar", "/lib/core.jar", "/lib/web.jar"), cp);
        assertEquals(0, java.nio.file.Files.walk(tmp)
                .filter(p -> p.getFileName().toString().equals("anatomist-classpath.txt"))
                .count(), "generated classpath files must be cleaned up");
    }

    private static Path extractOutputFile(List<String> args) {
        for (String a : args) {
            if (a.startsWith("-Dmdep.outputFile=")) {
                return Path.of(a.substring("-Dmdep.outputFile=".length()));
            }
        }
        throw new AssertionError("missing -Dmdep.outputFile arg");
    }

    @Test
    void detectJavaVersion_readsMavenCompilerSource(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"),
                "<project><properties>"
                + "<maven.compiler.source>17</maven.compiler.source>"
                + "</properties></project>");
        assertEquals(OptionalInt.of(17), new ClasspathDetector().detectJavaVersion(tmp));
    }

    @Test
    void detectJavaVersion_fallsBackToJavaVersionProperty(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"),
                "<project><properties>"
                + "<java.version>11</java.version>"
                + "</properties></project>");
        assertEquals(OptionalInt.of(11), new ClasspathDetector().detectJavaVersion(tmp));
    }

    @Test
    void detectJavaVersion_multiModuleReturnsMax(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"),
                "<project><modules><module>a</module><module>b</module></modules></project>");
        Path a = Files.createDirectories(tmp.resolve("a"));
        Path b = Files.createDirectories(tmp.resolve("b"));
        Files.writeString(a.resolve("pom.xml"),
                "<project><properties>"
                + "<maven.compiler.source>11</maven.compiler.source>"
                + "</properties></project>");
        Files.writeString(b.resolve("pom.xml"),
                "<project><properties>"
                + "<maven.compiler.source>17</maven.compiler.source>"
                + "</properties></project>");
        assertEquals(OptionalInt.of(17), new ClasspathDetector().detectJavaVersion(tmp));
    }

    @Test
    void detectJavaVersion_noPomReturnsEmpty(@TempDir Path tmp) {
        assertEquals(OptionalInt.empty(), new ClasspathDetector().detectJavaVersion(tmp));
    }
}
