package com.anatomist.core.asmsolver;

import com.anatomist.core.logging.AnatomistLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * One indexed class-file source for an entire dependency classpath.
 *
 * <p>Classpath order is significant: the first entry containing a class wins.
 * Jars are closed after their entry tables are indexed and reopened through a
 * small LRU only when bytes are requested. This avoids both a linear chain of
 * hundreds of type solvers and one permanently-open descriptor per jar.</p>
 */
public final class ClasspathClassFileSource implements ClassFileSource {

    private static final int MAX_OPEN_JARS = 32;

    static final String CACHE_DIR_PROPERTY = "anatomist.typeCache.dir";

    private final List<ClasspathEntry> origins = new ArrayList<>();
    private final Map<String, Integer> directoryOrigins = new LinkedHashMap<>();
    private final Map<Path, JarFile> openJars = new LinkedHashMap<>(16, 0.75f, true);
    private PackedClasspathIndex jarOrigins = PackedClasspathIndex.empty();
    private String fingerprint = "empty";

    public ClasspathClassFileSource(Iterable<Path> entries) {
        this(entries, 0);
    }

    public ClasspathClassFileSource(Iterable<Path> entries, int javaVersion) {
        if (entries == null) return;
        for (Path entry : entries) {
            if (entry == null) continue;
            Path normalized = entry.toAbsolutePath().normalize();
            if (Files.isDirectory(normalized)) origins.add(new ClasspathEntry(normalized, false));
            else if (Files.isRegularFile(normalized)) origins.add(new ClasspathEntry(normalized, true));
        }
        fingerprint = fingerprint(origins, javaVersion);
        Map<String, Integer> jarEntries = loadJarIndex();
        for (int i = 0; i < origins.size(); i++) {
            ClasspathEntry origin = origins.get(i);
            try {
                if (origin.jar) {
                    if (jarEntries == null) continue;
                    indexJar(i, origin, jarEntries);
                } else {
                    indexDirectory(i, origin);
                }
            } catch (IOException | RuntimeException e) {
                AnatomistLog.warn("failed to index classpath entry: " + origin.path
                        + " (" + e.getMessage() + ")");
            }
        }
        if (jarEntries != null) {
            jarOrigins = PackedClasspathIndex.from(jarEntries);
            writeJarIndex();
        }
    }

    @Override
    public Optional<byte[]> find(String fqn) {
        int directory = directoryOrigins.getOrDefault(fqn, -1);
        int jarOrigin = jarOrigins.findOrigin(fqn);
        int originId = directory < 0 ? jarOrigin
                : jarOrigin < 0 ? directory : Math.min(directory, jarOrigin);
        if (originId < 0 || originId >= origins.size()) return Optional.empty();
        ClasspathEntry origin = origins.get(originId);
        String classEntry = fqn.replace('.', '/') + ".class";
        try {
            if (!origin.jar) {
                return Optional.of(Files.readAllBytes(origin.path.resolve(classEntry)));
            }
            JarFile jar = openJar(origin.path);
            JarEntry entry = jar.getJarEntry(classEntry);
            if (entry == null) return Optional.empty();
            try (InputStream in = jar.getInputStream(entry)) {
                return Optional.of(in.readAllBytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read class " + fqn
                    + " from " + origin.path, e);
        }
    }

    @Override
    public Set<String> knownClasses() {
        Set<String> names = new HashSet<>(directoryOrigins.keySet());
        names.addAll(jarOrigins.decodedNames());
        return Collections.unmodifiableSet(names);
    }

    @Override
    public synchronized void close() {
        for (JarFile jar : openJars.values()) {
            try {
                jar.close();
            } catch (IOException ignore) {
                // best effort
            }
        }
        openJars.clear();
    }

    int indexedClassCount() { return directoryOrigins.size() + jarOrigins.size(); }
    int packedIndexBytes() { return jarOrigins.packedBytes(); }
    String fingerprint() { return fingerprint; }

    private void indexDirectory(int originId, ClasspathEntry origin) throws IOException {
        try (Stream<Path> walk = Files.walk(origin.path)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted()
                    .forEach(path -> {
                        String relative = origin.path.relativize(path).toString().replace('\\', '/');
                        if (ignoredClassEntry(relative)) return;
                        String fqn = toFqn(relative);
                        directoryOrigins.putIfAbsent(fqn, originId);
                    });
        }
    }

    private void indexJar(int originId, ClasspathEntry origin, Map<String, Integer> entries) throws IOException {
        try (JarFile jar = new JarFile(origin.path.toFile())) {
            for (var jarEntries = jar.entries(); jarEntries.hasMoreElements();) {
                JarEntry entry = jarEntries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class") || ignoredClassEntry(name)) continue;
                entries.putIfAbsent(toFqn(name), originId);
            }
        }
    }

    private synchronized JarFile openJar(Path path) throws IOException {
        JarFile existing = openJars.get(path);
        if (existing != null) return existing;
        JarFile opened = new JarFile(path.toFile());
        openJars.put(path, opened);
        if (openJars.size() > MAX_OPEN_JARS) {
            var eldest = openJars.entrySet().iterator().next();
            openJars.remove(eldest.getKey());
            eldest.getValue().close();
        }
        return opened;
    }

    private static boolean ignoredClassEntry(String name) {
        return name.startsWith("META-INF/")
                || name.equals("module-info.class")
                || name.endsWith("/module-info.class")
                || name.equals("package-info.class")
                || name.endsWith("/package-info.class");
    }

    private static String toFqn(String classEntry) {
        return classEntry.substring(0, classEntry.length() - ".class".length())
                .replace('/', '.');
    }

    private Map<String, Integer> loadJarIndex() {
        Path file = cacheFile();
        if (file == null || !Files.isRegularFile(file)) return new LinkedHashMap<>();
        try {
            jarOrigins = PackedClasspathIndex.read(file, fingerprint);
            AnatomistLog.debug("classpath type index: cache hit classes=" + jarOrigins.size()
                    + " packed_bytes=" + jarOrigins.packedBytes());
            return null;
        } catch (IOException | RuntimeException e) {
            AnatomistLog.debug("classpath type index: ignoring cache " + file + " ("
                    + e.getMessage() + ")");
            try { Files.deleteIfExists(file); } catch (IOException ignored) {}
            return new LinkedHashMap<>();
        }
    }

    private void writeJarIndex() {
        Path file = cacheFile();
        if (file == null) return;
        try {
            jarOrigins.write(file, fingerprint);
            AnatomistLog.debug("classpath type index: wrote classes=" + jarOrigins.size()
                    + " packed_bytes=" + jarOrigins.packedBytes());
        } catch (IOException e) {
            AnatomistLog.debug("classpath type index: write failed (" + e.getMessage() + ")");
        }
    }

    Path metadataCacheFile() {
        Path root = cacheRoot();
        return root == null ? null : root.resolve(fingerprint + ".types");
    }

    private Path cacheFile() {
        Path root = cacheRoot();
        return root == null ? null : root.resolve(fingerprint + ".origins");
    }

    private static Path cacheRoot() {
        String configured = System.getProperty(CACHE_DIR_PROPERTY);
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        String home = System.getProperty("user.home");
        return home == null || home.isBlank() ? null
                : Path.of(home, ".anatomist", "cache", "types");
    }

    private static String fingerprint(List<ClasspathEntry> entries, int javaVersion) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Integer.toString(javaVersion).getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < entries.size(); i++) {
                ClasspathEntry entry = entries.get(i);
                digest.update(Integer.toString(i).getBytes(StandardCharsets.UTF_8));
                digest.update(entry.path.toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) (entry.jar ? 1 : 0));
                if (entry.jar) {
                    try {
                        digest.update(Long.toString(Files.size(entry.path)).getBytes(StandardCharsets.UTF_8));
                        digest.update(Long.toString(Files.getLastModifiedTime(entry.path).toMillis())
                                .getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        digest.update("unreadable".getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One shared origin per classpath entry, rather than one object per class. */
    private record ClasspathEntry(Path path, boolean jar) {}
}
