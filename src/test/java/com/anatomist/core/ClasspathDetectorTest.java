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
    void detect_parsesClasspathFromMockedMvnOutput(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        String mockOutput = "/lib/a.jar" + File.pathSeparator + "/lib/b.jar";

        ClasspathDetector det = new ClasspathDetector() {
            @Override
            protected int runMvn(Path workingDir, List<String> args) throws IOException {
                Path out = extractOutputFile(args);
                Files.writeString(out, mockOutput);
                return 0;
            }
        };

        List<String> cp = det.detect(tmp);
        assertEquals(List.of("/lib/a.jar", "/lib/b.jar"), cp);
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
