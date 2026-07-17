package com.anatomist.core;

import com.anatomist.core.logging.AnatomistLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HexFormat;
import java.util.OptionalInt;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class ClasspathDetector {

    static final String MAVEN_JAVA_HOME_ENV = "ANATOMIST_MAVEN_JAVA_HOME";
    private static final int MAVEN_OUTPUT_TAIL_BYTES = 64 * 1024;
    private volatile String lastMavenOutput = "";
    private Path forcedMavenJavaHome;

    /** Filename written by {@code dependency:build-classpath}. Relative (not
     *  absolute) on purpose: in a multi-module reactor Maven runs the goal once
     *  per module and a relative path lands one file in *each* module's basedir,
     *  letting us union the full reactor classpath. An absolute path would be
     *  overwritten by every module, leaving only the last one's deps. */
    static final String CP_FILE = "anatomist-classpath.txt";

    public List<String> detect(Path projectRoot) {
        return detectResult(projectRoot).entries();
    }

    public ClasspathDetectionResult detectResult(Path projectRoot) {
        if (projectRoot == null || !isMavenProject(projectRoot)) {
            return ClasspathDetectionResult.notRequested();
        }
        List<String> cached = readClasspathCache(projectRoot);
        if (!cached.isEmpty()) {
            java.util.LinkedHashSet<String> union = new java.util.LinkedHashSet<>(cached);
            detectBuildOutputClasspath(projectRoot).forEach(path -> union.add(path.toString()));
            AnatomistLog.debug("classpath: cache hit with " + cached.size() + " dependency entries");
            return ClasspathDetectionResult.cacheHit(new ArrayList<>(union));
        }
        // Clear any stragglers from a prior interrupted run so the union is clean.
        deleteClasspathFiles(projectRoot);
        try {
            List<String> mvnArgs = Arrays.asList(
                    "dependency:build-classpath",
                    // scope=test is the widest Maven scope (compile + provided +
                    // runtime + system + test); a code-intelligence index wants to
                    // resolve as many types as possible, and extra jars are nearly
                    // free for AsmTypeSolver.
                    "-DincludeScope=test",
                    // fail-at-end: one unresolvable module (e.g. a legacy
                    // system-scope tools.jar dep that's gone on JDK 9+) must not
                    // zero out the whole reactor's classpath. Good modules still
                    // write their per-module file; we union whatever landed.
                    "-fae",
                    "-q",
                    "-Dmdep.outputFile=" + CP_FILE
            );
            AnatomistLog.debug("classpath: running 'mvn " + String.join(" ", mvnArgs)
                    + "' in " + projectRoot);
            int code = runMvn(projectRoot, mvnArgs);
            List<Path> cpFiles = findClasspathFiles(projectRoot);
            if (code != 0 && !hasClasspathEntries(cpFiles) && needsLegacyJdkRetry(lastMavenOutput)) {
                Path java8 = findJava8Home();
                if (java8 != null && !java8.equals(selectMavenJavaHome(projectRoot))) {
                    AnatomistLog.debug("classpath: retrying Maven with legacy JDK " + java8);
                    deleteClasspathFiles(projectRoot);
                    forcedMavenJavaHome = java8;
                    try {
                        code = runMvn(projectRoot, mvnArgs);
                    } finally {
                        forcedMavenJavaHome = null;
                    }
                    cpFiles = findClasspathFiles(projectRoot);
                }
            }
            // Union every module's output, preserving first-seen order. Done even
            // on non-zero exit: with -fae the modules that succeeded already wrote
            // their files, and a partial classpath beats an empty one.
            java.util.LinkedHashSet<String> union = new java.util.LinkedHashSet<>();
            AnatomistLog.debug("classpath: mvn exited " + code + "; "
                    + cpFiles.size() + " per-module classpath file(s) written");
            for (Path f : cpFiles) {
                String content = Files.readString(f, StandardCharsets.UTF_8).trim();
                if (content.isEmpty()) continue;
                int countBefore = union.size();
                for (String entry : content.split(java.io.File.pathSeparator)) {
                    String t = entry.trim();
                    if (!t.isEmpty()) union.add(t);
                }
                AnatomistLog.debug("classpath:   " + projectRoot.relativize(f.getParent())
                        + " contributed " + (union.size() - countBefore) + " new jar(s)");
            }
            if (code != 0) {
                warn("mvn dependency:build-classpath exited with code " + code
                        + "; proceeding with classpath from the " + union.size()
                        + " entries that did resolve (some modules failed)"
                        + failureOutputSuffix());
            }
            if (code == 0 && !union.isEmpty()) writeClasspathCache(projectRoot, union);
            for (Path output : detectBuildOutputClasspath(projectRoot)) {
                union.add(output.toString());
            }
            AnatomistLog.debug("classpath: union total = " + union.size() + " entrie(s)");
            if (code == 0) {
                return new ClasspathDetectionResult(
                        ClasspathDetectionResult.Status.FULL,
                        new ArrayList<>(union), code, cpFiles.size(), null, List.of());
            }
            String sample = boundedMavenSample(lastMavenOutput);
            String diagnosticCode = union.isEmpty()
                    ? "CLASSPATH_UNAVAILABLE" : "CLASSPATH_PARTIAL";
            String message = union.isEmpty()
                    ? "Maven classpath detection failed and produced no usable entries."
                    : "Maven classpath detection was partial; successful module outputs were retained.";
            IndexDiagnostic diagnostic = new IndexDiagnostic(
                    "warning", diagnosticCode, "CLASSPATH",
                    null, null, null, null, 1,
                    sample == null ? message : message + " " + sample);
            return new ClasspathDetectionResult(
                    union.isEmpty()
                            ? ClasspathDetectionResult.Status.UNAVAILABLE
                            : ClasspathDetectionResult.Status.PARTIAL,
                    new ArrayList<>(union), code, cpFiles.size(), sample,
                    List.of(diagnostic));
        } catch (IOException | InterruptedException e) {
            warn("mvn classpath detection failed (" + e.getMessage() + "), proceeding with empty classpath");
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            String sample = boundedMavenSample(e.getMessage());
            return new ClasspathDetectionResult(
                    ClasspathDetectionResult.Status.UNAVAILABLE,
                    List.of(), null, 0, sample,
                    List.of(new IndexDiagnostic(
                            "warning", "CLASSPATH_UNAVAILABLE", "CLASSPATH",
                            null, null, null, null, 1,
                            "Maven classpath detection failed. "
                                    + (sample == null ? "" : sample))));
        } finally {
            deleteClasspathFiles(projectRoot);
        }
    }

    private static String boundedMavenSample(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.strip();
        int start = Math.max(0, value.length() - 500);
        value = value.substring(start);
        StringBuilder safe = new StringBuilder(value.length());
        int cursor = 0;
        while (cursor < value.length()) {
            int scheme = value.indexOf("://", cursor);
            if (scheme < 0) {
                safe.append(value, cursor, value.length());
                break;
            }
            safe.append(value, cursor, scheme + 3);
            int credentialsEnd = value.indexOf('@', scheme + 3);
            int boundary = firstBoundary(value, scheme + 3);
            if (credentialsEnd >= 0 && (boundary < 0 || credentialsEnd < boundary)) {
                safe.append("***@");
                cursor = credentialsEnd + 1;
            } else {
                cursor = scheme + 3;
            }
        }
        return safe.toString();
    }

    private static int firstBoundary(String value, int start) {
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == '/' || c == '\\') return i;
        }
        return -1;
    }

    private static boolean hasClasspathEntries(List<Path> files) {
        for (Path file : files) {
            try {
                if (!Files.readString(file, StandardCharsets.UTF_8).isBlank()) return true;
            } catch (IOException ignore) {
                // Treat unreadable output as unusable and allow the retry.
            }
        }
        return false;
    }

    private List<String> readClasspathCache(Path projectRoot) {
        Path file = classpathCacheFile(projectRoot);
        if (file == null || !Files.isRegularFile(file)) return List.of();
        try {
            List<String> entries = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .toList();
            if (entries.isEmpty()) return List.of();
            boolean valid = entries.stream().map(Path::of).allMatch(Files::isRegularFile);
            if (!valid) {
                Files.deleteIfExists(file);
                return List.of();
            }
            return entries;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private void writeClasspathCache(Path projectRoot, java.util.Set<String> entries) {
        Path file = classpathCacheFile(projectRoot);
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), "classpath-", ".tmp");
            Files.write(temporary, entries, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            AnatomistLog.debug("classpath: failed to write cache: " + e.getMessage());
        }
    }

    private Path classpathCacheFile(Path projectRoot) {
        String key = classpathCacheKey(projectRoot);
        Path root = classpathCacheRoot();
        return key == null || root == null ? null : root.resolve(key + ".txt");
    }

    protected Path classpathCacheRoot() {
        String configured = systemProperty("anatomist.classpath.cache.dir");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        String anatomistHome = environment("ANATOMIST_HOME");
        if (anatomistHome != null && !anatomistHome.isBlank()) {
            return Path.of(anatomistHome, "cache", "classpath");
        }
        String home = systemProperty("user.home");
        return home == null || home.isBlank() ? null
                : Path.of(home, ".anatomist", "cache", "classpath");
    }

    private String classpathCacheKey(Path projectRoot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Path normalized = projectRoot.toAbsolutePath().normalize();
            digest.update(normalized.toString().getBytes(StandardCharsets.UTF_8));
            String override = environment(MAVEN_JAVA_HOME_ENV);
            if (override != null) digest.update(override.getBytes(StandardCharsets.UTF_8));
            List<Path> poms;
            try (Stream<Path> walk = Files.walk(normalized)) {
                poms = walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName() != null
                                && "pom.xml".equals(path.getFileName().toString()))
                        .sorted().toList();
            }
            for (Path pom : poms) {
                digest.update(normalized.relativize(pom).toString().getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(pom));
            }
            String home = systemProperty("user.home");
            if (home != null) {
                Path settings = Path.of(home, ".m2", "settings.xml");
                if (Files.isRegularFile(settings)) digest.update(Files.readAllBytes(settings));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
            return null;
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
        return detectSourcePaths(projectRoot, false);
    }

    /**
     * Discover source roots. When {@code includeTests} is true, each module's
     * {@code src/test/java} is collected alongside {@code src/main/java} — and
     * test-only modules (no {@code src/main/java} of their own) are picked up
     * too. Within a module the main root sorts before its test root.
     */
    public List<Path> detectSourcePaths(Path projectRoot, boolean includeTests) {
        if (projectRoot == null) return Collections.emptyList();
        if (isMavenProject(projectRoot)) {
            Path mainSrc = projectRoot.resolve("src/main/java");
            Path testSrc = projectRoot.resolve("src/test/java");
            boolean hasMain = Files.isDirectory(mainSrc);
            boolean hasTest = Files.isDirectory(testSrc);
            if (hasMain) {
                List<Path> out = new ArrayList<>();
                out.add(mainSrc);
                out.addAll(findGeneratedSourceRoots(projectRoot));
                if (includeTests && hasTest) out.add(testSrc);
                return out;
            }
            // Multi-module reactor: no src/main/java at the root, so collect
            // every module's source root(s) instead.
            List<Path> modules = findModuleSourceRoots(projectRoot, includeTests);
            if (!modules.isEmpty()) return modules;
            return Collections.emptyList();
        }
        return Files.isDirectory(projectRoot) ? List.of(projectRoot) : Collections.emptyList();
    }

    /** Walk the reactor for every {@code src/main/java} (and, when requested,
     *  {@code src/test/java}) directory, skipping build-output and VCS trees.
     *  Returns sorted, deduplicated roots. */
    private List<Path> findModuleSourceRoots(Path projectRoot, boolean includeTests) {
        Path mainTail = Path.of("src", "main", "java");
        Path testTail = Path.of("src", "test", "java");
        try {
            java.util.LinkedHashSet<Path> roots = new java.util.LinkedHashSet<>();
            Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(projectRoot) && isUnderExcludedDir(projectRoot.relativize(dir))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (dir.endsWith(mainTail) || (includeTests && dir.endsWith(testTail))) {
                        roots.add(dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            roots.addAll(findGeneratedSourceRoots(projectRoot));
            return roots.stream().sorted().toList();
        } catch (IOException e) {
            warn("failed to scan for module source roots under " + projectRoot
                    + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<Path> detectBuildOutputClasspath(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return Collections.emptyList();
        Path classesTail = Path.of("target", "classes");
        Path testClassesTail = Path.of("target", "test-classes");
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            return walk.filter(Files::isDirectory)
                    .filter(p -> p.endsWith(classesTail) || p.endsWith(testClassesTail))
                    .filter(p -> !isNestedUnderBuildOutput(projectRoot.relativize(p)))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            warn("failed to scan for build output classpath under " + projectRoot
                    + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Path> findGeneratedSourceRoots(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return Collections.emptyList();
        try {
            List<Path> roots = new ArrayList<>();
            Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(projectRoot)) return FileVisitResult.CONTINUE;
                    Path relative = projectRoot.relativize(dir);
                    if (containsHardExcludedDir(relative)) return FileVisitResult.SKIP_SUBTREE;
                    Path parent = dir.getParent();
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (parent != null && "target".equals(parent.getFileName().toString())
                            && !"generated-sources".equals(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (parent != null && "generated-sources".equals(parent.getFileName().toString())) {
                        Path grand = parent.getParent();
                        if (grand != null && "target".equals(grand.getFileName().toString())) {
                            if (isGeneratedJavaSourceRoot(dir)) roots.add(dir);
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            roots.sort(Path::compareTo);
            return roots;
        } catch (IOException e) {
            warn("failed to scan for generated source roots under " + projectRoot
                    + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private boolean isGeneratedJavaSourceRoot(Path p) {
        String s = p.toString().replace('\\', '/');
        if (!s.contains("/target/generated-sources/")) return false;
        Path parent = p.getParent();
        if (parent == null || !"generated-sources".equals(parent.getFileName().toString())) return false;
        Path grand = parent.getParent();
        if (grand == null || !"target".equals(grand.getFileName().toString())) return false;
        try (Stream<Path> children = Files.walk(p)) {
            return children.anyMatch(child -> Files.isRegularFile(child)
                    && child.getFileName().toString().endsWith(".java"));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isNestedUnderBuildOutput(Path relative) {
        int targetClassesSeen = 0;
        String prev = "";
        for (Path part : relative) {
            String name = part.toString();
            if ("classes".equals(name) && "target".equals(prev)) targetClassesSeen++;
            if ("test-classes".equals(name) && "target".equals(prev)) targetClassesSeen++;
            if (targetClassesSeen > 1) return true;
            prev = name;
        }
        return false;
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

    private static boolean containsHardExcludedDir(Path relative) {
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals("build") || name.equals(".gradle") || name.equals(".git")
                    || name.equals(".idea") || name.equals("node_modules")) return true;
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
        JavaVersionDetection detection = detectJavaVersionDetailed(projectRoot);
        return detection.found() ? OptionalInt.of(detection.version()) : OptionalInt.empty();
    }

    public JavaVersionDetection detectJavaVersionDetailed(Path projectRoot) {
        return BuildJavaVersionDetector.detect(projectRoot);
    }

    /**
     * Test seam — subclasses may override to avoid spawning a real Maven process.
     */
    protected int runMvn(Path workingDir, List<String> args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(mavenExecutable());
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workingDir.toFile())
                .redirectErrorStream(true);
        Path javaHome = selectMavenJavaHome(workingDir);
        if (javaHome != null) {
            pb.environment().put("JAVA_HOME", javaHome.toString());
            String path = pb.environment().getOrDefault("PATH", "");
            pb.environment().put("PATH", javaHome.resolve("bin")
                    + java.io.File.pathSeparator + path);
            AnatomistLog.debug("classpath: Maven JAVA_HOME=" + javaHome);
        }
        Process p = pb.start();
        TailOutputStream tail = new TailOutputStream(MAVEN_OUTPUT_TAIL_BYTES);
        Thread drain = Thread.ofVirtual().name("anatomist-maven-output").start(() -> {
            try (InputStream in = p.getInputStream()) {
                in.transferTo(tail);
            } catch (IOException ignore) {
                // Process shutdown can close the pipe while it is being drained.
            }
        });
        if (!p.waitFor(300, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            drain.join(5_000);
            setLastMavenOutput(tail.asString());
            throw new IOException("mvn timed out after 300s");
        }
        drain.join(5_000);
        setLastMavenOutput(tail.asString());
        return p.exitValue();
    }

    protected void setLastMavenOutput(String output) {
        lastMavenOutput = output == null ? "" : output;
    }

    /** Select a Maven runtime without changing anatomist's own JVM. */
    protected Path selectMavenJavaHome(Path projectRoot) {
        if (forcedMavenJavaHome != null) return forcedMavenJavaHome;
        String override = environment(MAVEN_JAVA_HOME_ENV);
        Path explicit = usableJavaHome(override, false);
        if (explicit != null) return explicit;
        if (override != null && !override.isBlank()) {
            warn(MAVEN_JAVA_HOME_ENV + " is not a usable JDK home: " + override);
        }

        OptionalInt projectVersion = detectJavaVersion(projectRoot);
        boolean legacyToolsJar = requiresLegacyToolsJar(projectRoot);
        if (!legacyToolsJar && (projectVersion.isEmpty() || projectVersion.getAsInt() > 8)) return null;

        return findJava8Home();
    }

    private Path findJava8Home() {
        Path current = usableJavaHome(systemProperty("java.home"), true);
        if (current != null) return current;
        Path envJdk8 = usableJavaHome(environment("JAVA8_HOME"), true);
        if (envJdk8 != null) return envJdk8;
        Path macJdk8 = usableJavaHome(discoverMacJava8Home(), true);
        return macJdk8 != null ? macJdk8 : discoverSdkmanJava8Home();
    }

    private static boolean needsLegacyJdkRetry(String output) {
        if (output == null || output.isBlank()) return false;
        return output.contains("tools.jar") || output.contains("jdk.tools:jdk.tools");
    }

    /** Legacy system-scope jdk.tools dependencies require a JDK 8 Maven runtime. */
    private boolean requiresLegacyToolsJar(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return false;
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            for (Path pom : walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName() != null
                            && "pom.xml".equals(path.getFileName().toString()))
                    .toList()) {
                try {
                    String xml = Files.readString(pom, StandardCharsets.UTF_8);
                    if (xml.contains("tools.jar") || xml.contains("<artifactId>jdk.tools</artifactId>")) {
                        return true;
                    }
                } catch (IOException ignore) {
                    // Continue scanning other module poms.
                }
            }
        } catch (IOException ignore) {
            return false;
        }
        return false;
    }

    protected String environment(String name) {
        return System.getenv(name);
    }

    protected String mavenExecutable() {
        return "mvn";
    }

    protected String systemProperty(String name) {
        return System.getProperty(name);
    }

    protected String discoverMacJava8Home() {
        Path command = Path.of("/usr/libexec/java_home");
        if (!Files.isExecutable(command)) return null;
        try {
            Process p = new ProcessBuilder(command.toString(), "-v", "1.8")
                    .redirectErrorStream(true).start();
            String output;
            try (InputStream in = p.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 ? output : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private Path discoverSdkmanJava8Home() {
        String home = systemProperty("user.home");
        if (home == null || home.isBlank()) return null;
        Path candidates = Path.of(home, ".sdkman", "candidates", "java");
        if (!Files.isDirectory(candidates)) return null;
        try (Stream<Path> children = Files.list(candidates)) {
            return children.sorted()
                    .map(path -> usableJavaHome(path.toString(), true))
                    .filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static Path usableJavaHome(String raw, boolean requireToolsJar) {
        if (raw == null || raw.isBlank()) return null;
        Path home;
        try {
            home = Path.of(raw.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
        if ("jre".equals(home.getFileName() == null ? "" : home.getFileName().toString())) {
            Path parent = home.getParent();
            if (parent != null) home = parent;
        }
        if (!Files.isExecutable(home.resolve("bin/java"))) return null;
        if (requireToolsJar && !Files.isRegularFile(home.resolve("lib/tools.jar"))) return null;
        return home;
    }

    private String failureOutputSuffix() {
        String output = lastMavenOutput == null ? "" : lastMavenOutput.strip();
        if (output.isEmpty()) {
            return "; set " + MAVEN_JAVA_HOME_ENV + " when the project requires a specific JDK";
        }
        String[] lines = output.split("\\R");
        int start = Math.max(0, lines.length - 20);
        return "; Maven output:\n" + String.join("\n", Arrays.copyOfRange(lines, start, lines.length));
    }

    /** Bounded ring buffer used only for the tail of Maven diagnostics. */
    private static final class TailOutputStream extends OutputStream {
        private final byte[] bytes;
        private int start;
        private int size;

        private TailOutputStream(int capacity) {
            this.bytes = new byte[capacity];
        }

        @Override
        public synchronized void write(int value) {
            if (size < bytes.length) {
                bytes[(start + size) % bytes.length] = (byte) value;
                size++;
            } else {
                bytes[start] = (byte) value;
                start = (start + 1) % bytes.length;
            }
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            for (int i = 0; i < length; i++) write(buffer[offset + i]);
        }

        private synchronized String asString() {
            byte[] out = new byte[size];
            for (int i = 0; i < size; i++) out[i] = bytes[(start + i) % bytes.length];
            return new String(out, StandardCharsets.UTF_8);
        }
    }

    private static void warn(String msg) {
        System.err.println("WARN: " + msg);
    }
}
