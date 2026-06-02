package com.anatomist.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

public class ClasspathDetector {

    /** Filename written by {@code dependency:build-classpath}. Relative (not
     *  absolute) on purpose: in a multi-module reactor Maven runs the goal once
     *  per module and a relative path lands one file in *each* module's basedir,
     *  letting us union the full reactor classpath. An absolute path would be
     *  overwritten by every module, leaving only the last one's deps. */
    static final String CP_FILE = "anatomist-classpath.txt";

    public List<String> detect(Path projectRoot) {
        if (projectRoot == null || !isMavenProject(projectRoot)) {
            return Collections.emptyList();
        }
        // Clear any stragglers from a prior interrupted run so the union is clean.
        deleteClasspathFiles(projectRoot);
        try {
            int code = runMvn(projectRoot, Arrays.asList(
                    "dependency:build-classpath",
                    // scope=test is the widest Maven scope (compile + provided +
                    // runtime + system + test); a code-intelligence index wants to
                    // resolve as many types as possible, and extra jars are nearly
                    // free for AsmTypeSolver.
                    "-DincludeScope=test",
                    "-q",
                    "-Dmdep.outputFile=" + CP_FILE
            ));
            if (code != 0) {
                warn("mvn dependency:build-classpath exited with code " + code
                        + ", proceeding with empty classpath");
                return Collections.emptyList();
            }
            // Union every module's output, preserving first-seen order.
            java.util.LinkedHashSet<String> union = new java.util.LinkedHashSet<>();
            for (Path f : findClasspathFiles(projectRoot)) {
                String content = Files.readString(f, StandardCharsets.UTF_8).trim();
                if (content.isEmpty()) continue;
                for (String entry : content.split(java.io.File.pathSeparator)) {
                    String t = entry.trim();
                    if (!t.isEmpty()) union.add(t);
                }
            }
            return new ArrayList<>(union);
        } catch (IOException | InterruptedException e) {
            warn("mvn classpath detection failed (" + e.getMessage() + "), proceeding with empty classpath");
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Collections.emptyList();
        } finally {
            deleteClasspathFiles(projectRoot);
        }
    }

    /** Locate every per-module {@link #CP_FILE} under the reactor, sorted for
     *  deterministic union order. */
    private List<Path> findClasspathFiles(Path projectRoot) {
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName() != null
                            && CP_FILE.equals(p.getFileName().toString()))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            warn("failed to collect classpath files under " + projectRoot + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Remove generated {@link #CP_FILE} files so they don't pollute the work tree. */
    private void deleteClasspathFiles(Path projectRoot) {
        for (Path f : findClasspathFiles(projectRoot)) {
            try {
                Files.deleteIfExists(f);
            } catch (IOException ignore) {
                // best-effort cleanup
            }
        }
    }

    public List<Path> detectSourcePaths(Path projectRoot) {
        if (projectRoot == null) return Collections.emptyList();
        if (isMavenProject(projectRoot)) {
            Path src = projectRoot.resolve("src/main/java");
            if (Files.isDirectory(src)) {
                return List.of(src);
            }
            // Multi-module reactor: no src/main/java at the root, so collect
            // every module's main source root instead.
            List<Path> modules = findModuleSourceRoots(projectRoot);
            if (!modules.isEmpty()) return modules;
            return Collections.emptyList();
        }
        return Files.isDirectory(projectRoot) ? List.of(projectRoot) : Collections.emptyList();
    }

    /** Walk the reactor for every {@code src/main/java} directory, skipping
     *  build-output and VCS trees. Returns sorted, deduplicated roots. */
    private List<Path> findModuleSourceRoots(Path projectRoot) {
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            return walk
                    .filter(Files::isDirectory)
                    .filter(p -> p.endsWith(Path.of("src", "main", "java")))
                    .filter(p -> !isUnderExcludedDir(projectRoot.relativize(p)))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            warn("failed to scan for module source roots under " + projectRoot
                    + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private static boolean isUnderExcludedDir(Path relative) {
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals("target") || name.equals("build")
                    || name.equals(".git") || name.equals("node_modules")) {
                return true;
            }
        }
        return false;
    }

    protected boolean isMavenProject(Path root) {
        return Files.isRegularFile(root.resolve("pom.xml"));
    }

    /**
     * Detect the highest Java language version declared across every pom.xml in
     * the project tree, reading {@code <maven.compiler.source>} (preferred) or
     * {@code <java.version>} property. Returns {@link OptionalInt#empty()} when
     * no pom.xml is present or no version is declared.
     */
    public OptionalInt detectJavaVersion(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return OptionalInt.empty();
        List<Path> poms;
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            poms = walk.filter(p -> p.getFileName() != null
                            && "pom.xml".equals(p.getFileName().toString()))
                    .filter(Files::isRegularFile)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return OptionalInt.empty();
        }
        if (poms.isEmpty()) return OptionalInt.empty();

        SAXParserFactory spf = SAXParserFactory.newInstance();
        spf.setNamespaceAware(false);
        int max = -1;
        for (Path pom : poms) {
            OptionalInt v = readJavaVersion(spf, pom);
            if (v.isPresent() && v.getAsInt() > max) max = v.getAsInt();
        }
        return max < 0 ? OptionalInt.empty() : OptionalInt.of(max);
    }

    private static OptionalInt readJavaVersion(SAXParserFactory spf, Path pom) {
        try {
            SAXParser parser = spf.newSAXParser();
            VersionHandler h = new VersionHandler();
            parser.parse(pom.toFile(), h);
            if (h.compilerSource > 0) return OptionalInt.of(h.compilerSource);
            if (h.javaVersion > 0) return OptionalInt.of(h.javaVersion);
        } catch (Exception ignore) {
            // malformed pom — pretend not declared
        }
        return OptionalInt.empty();
    }

    private static int parseVersion(String raw) {
        if (raw == null) return -1;
        String s = raw.trim();
        // Accept "17", "1.8", "11.0".
        if (s.startsWith("1.")) s = s.substring(2);
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return -1; }
    }

    private static final class VersionHandler extends DefaultHandler {
        int compilerSource = -1;
        int javaVersion = -1;
        private final StringBuilder buf = new StringBuilder();
        private boolean inProperties = false;
        private String current = null;

        @Override public void startElement(String uri, String localName, String qName, Attributes attributes) {
            if ("properties".equals(qName)) inProperties = true;
            if (inProperties) current = qName;
            buf.setLength(0);
        }

        @Override public void characters(char[] ch, int start, int length) {
            if (inProperties && current != null) buf.append(ch, start, length);
        }

        @Override public void endElement(String uri, String localName, String qName) {
            if (inProperties && current != null) {
                String val = buf.toString();
                if ("maven.compiler.source".equals(qName)) {
                    int v = parseVersion(val);
                    if (v > compilerSource) compilerSource = v;
                } else if ("java.version".equals(qName)) {
                    int v = parseVersion(val);
                    if (v > javaVersion) javaVersion = v;
                }
                current = null;
                buf.setLength(0);
            }
            if ("properties".equals(qName)) inProperties = false;
        }
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
        if (!p.waitFor(300, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("mvn timed out after 300s");
        }
        return p.exitValue();
    }

    private static void warn(String msg) {
        System.err.println("WARN: " + msg);
    }
}
