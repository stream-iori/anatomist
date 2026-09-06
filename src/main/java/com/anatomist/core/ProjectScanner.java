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
    private final ScanPolicy scanPolicy;

    public ProjectScanner() {
        this(Collections.emptySet(), null);
    }

    public ProjectScanner(Set<String> additionalExcludes) {
        this(additionalExcludes, null);
    }

    public ProjectScanner(Set<String> additionalExcludes, ScanPolicy scanPolicy) {
        Set<String> merged = new HashSet<>(DEFAULT_EXCLUDES);
        if (additionalExcludes != null) merged.addAll(additionalExcludes);
        this.excludedDirs = Collections.unmodifiableSet(merged);
        this.scanPolicy = scanPolicy;
    }

    public Set<String> excludedDirs() {
        return excludedDirs;
    }

    public List<Path> scan(Path root) {
        return scan(root, false, Set.of(".java"));
    }

    private List<Path> scan(Path root, boolean trustedRoot, Set<String> extensions) {
        if (root == null || !Files.isDirectory(root)) {
            return Collections.emptyList();
        }
        if (!trustedRoot && containsExcludedDir(root.normalize())) {
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
                    String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    if (attrs.isRegularFile() && extensions.stream().anyMatch(name::endsWith)
                            && (scanPolicy == null || scanPolicy.includes(file))) {
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

    /** Scan resolved source roots while allowing an explicitly classified generated root
     *  to live below Maven's target directory. Ordinary target trees remain excluded. */
    public List<Path> scanSourceRoots(List<SourceRoot> roots) {
        return scanSourceRoots(roots, Set.of(".java"));
    }

    public List<Path> scanSourceRoots(List<SourceRoot> roots, Set<String> extensions) {
        Set<Path> unique = new java.util.LinkedHashSet<>();
        if (roots == null) return List.of();
        Set<String> normalizedExtensions = extensions == null || extensions.isEmpty()
                ? Set.of(".java") : extensions.stream()
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (SourceRoot root : roots) {
            unique.addAll(scan(root.path(), root.scope() == SourceScope.GENERATED,
                    normalizedExtensions));
        }
        List<Path> out = new ArrayList<>(unique);
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
                            && (scanPolicy == null || scanPolicy.includes(file))
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
