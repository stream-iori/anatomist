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
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(this::notInExcludedDir)
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

    private boolean notInExcludedDir(Path path) {
        for (Path part : path) {
            if (excludedDirs.contains(part.toString())) return false;
        }
        return true;
    }
}
