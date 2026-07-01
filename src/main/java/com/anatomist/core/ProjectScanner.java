package com.anatomist.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class ProjectScanner {

    public static final Set<String> DEFAULT_EXCLUDES = Set.of(
            "target", "build", ".gradle", ".git", ".idea", "node_modules"
    );

    private final Set<String> excludedDirs;

    public ProjectScanner() {
        this(Collections.emptySet());
    }

    public ProjectScanner(Set<String> additionalExcludes) {
        Set<String> merged = new HashSet<>(DEFAULT_EXCLUDES);
        if (additionalExcludes != null) merged.addAll(additionalExcludes);
        this.excludedDirs = Collections.unmodifiableSet(merged);
    }

    public Set<String> excludedDirs() {
        return excludedDirs;
    }

    public List<Path> scan(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return Collections.emptyList();
        }
        if (containsExcludedDir(root.normalize())) {
            return Collections.emptyList();
        }
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(p -> notInExcludedDir(root.relativize(p)))
                    .filter(p -> Files.isRegularFile(p))
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(out::add);
        } catch (IOException e) {
            throw new RuntimeException("Failed scanning " + root, e);
        }
        return out;
    }

    public List<Path> scan(List<Path> roots) {
        List<Path> out = new ArrayList<>();
        for (Path r : roots) out.addAll(scan(r));
        return out;
    }

    /**
     * Discover Spring bean XML configs under {@code root}: regular {@code .xml}
     * files whose root element is a Spring {@code <beans>} element (sniffed via
     * {@link SpringBeanParser#isSpringBeansFile}). Honours the same excluded-dir
     * rules as {@link #scan(Path)}. Used only when {@code --spring-xml} is on.
     */
    public List<Path> scanSpringXml(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return Collections.emptyList();
        }
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(p -> notInExcludedDir(root.relativize(p)))
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".xml"))
                    .filter(SpringBeanParser::isSpringBeansFile)
                    .forEach(out::add);
        } catch (IOException e) {
            throw new RuntimeException("Failed scanning " + root, e);
        }
        return out;
    }

    public List<Path> scanSpringXml(List<Path> roots) {
        List<Path> out = new ArrayList<>();
        for (Path r : roots) out.addAll(scanSpringXml(r));
        return out;
    }

    private boolean notInExcludedDir(Path path) {
        return !containsExcludedDir(path);
    }

    private boolean containsExcludedDir(Path path) {
        for (Path part : path) {
            if (excludedDirs.contains(part.toString())) return true;
        }
        return false;
    }
}
