package com.anatomist.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ClasspathDetector {

    public List<String> detect(Path projectRoot) {
        if (projectRoot == null || !isMavenProject(projectRoot)) {
            return Collections.emptyList();
        }
        Path outFile;
        try {
            outFile = Files.createTempFile("anatomist-cp-", ".txt");
        } catch (IOException e) {
            warn("failed to create temp file for mvn classpath: " + e.getMessage());
            return Collections.emptyList();
        }
        try {
            int code = runMvn(projectRoot, Arrays.asList(
                    "dependency:build-classpath",
                    "-DincludeScope=compile",
                    "-q",
                    "-Dmdep.outputFile=" + outFile.toAbsolutePath()
            ));
            if (code != 0) {
                warn("mvn dependency:build-classpath exited with code " + code
                        + ", proceeding with empty classpath");
                return Collections.emptyList();
            }
            String content = Files.readString(outFile, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) return Collections.emptyList();
            return Arrays.stream(content.split(java.io.File.pathSeparator))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        } catch (IOException | InterruptedException e) {
            warn("mvn classpath detection failed (" + e.getMessage() + "), proceeding with empty classpath");
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Collections.emptyList();
        } finally {
            try {
                Files.deleteIfExists(outFile);
            } catch (IOException ignore) {
            }
        }
    }

    public List<Path> detectSourcePaths(Path projectRoot) {
        if (projectRoot == null) return Collections.emptyList();
        if (isMavenProject(projectRoot)) {
            Path src = projectRoot.resolve("src/main/java");
            return Files.isDirectory(src)
                    ? List.of(src)
                    : Collections.emptyList();
        }
        return Files.isDirectory(projectRoot) ? List.of(projectRoot) : Collections.emptyList();
    }

    protected boolean isMavenProject(Path root) {
        return Files.isRegularFile(root.resolve("pom.xml"));
    }

    /**
     * Test seam — subclasses may override to avoid spawning a real Maven process.
     */
    protected int runMvn(Path workingDir, List<String> args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("mvn");
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workingDir.toFile())
                .redirectErrorStream(true);
        Process p = pb.start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("mvn timed out after 60s");
        }
        return p.exitValue();
    }

    private static void warn(String msg) {
        System.err.println("WARN: " + msg);
    }
}
