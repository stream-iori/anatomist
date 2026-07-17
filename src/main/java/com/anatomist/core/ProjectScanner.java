package com.anatomist.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && containsExcludedDir(root.relativize(dir))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".java")) {
                        out.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed scanning " + root, e);
        }
        out.sort(java.util.Comparator.comparing(
                path -> path.toAbsolutePath().normalize().toString()));
        return out;
    }

    public List<Path> scan(List<Path> roots) {
        List<Path> out = new ArrayList<>();
        for (Path r : roots) out.addAll(scan(r));
        out.sort(java.util.Comparator.comparing(
                path -> path.toAbsolutePath().normalize().toString()));
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
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && containsExcludedDir(root.relativize(dir))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".xml")
                            && SpringBeanParser.isSpringBeansFile(file)) {
                        out.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed scanning " + root, e);
        }
        out.sort(java.util.Comparator.comparing(
                path -> path.toAbsolutePath().normalize().toString()));
        return out;
    }

    public List<Path> scanSpringXml(List<Path> roots) {
        List<Path> out = new ArrayList<>();
        for (Path r : roots) out.addAll(scanSpringXml(r));
        out.sort(java.util.Comparator.comparing(
                path -> path.toAbsolutePath().normalize().toString()));
        return out;
    }

    private boolean containsExcludedDir(Path path) {
        for (Path part : path) {
            if (excludedDirs.contains(part.toString())) return true;
        }
        return false;
    }
}
