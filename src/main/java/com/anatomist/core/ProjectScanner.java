package com.anatomist.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public class ProjectScanner {

    private final Set<String> excludedDirs;

    public ProjectScanner(Set<String> excludedDirs) {
        this.excludedDirs = excludedDirs;
    }

    public List<Path> scan(Path root) {
        throw new UnsupportedOperationException("not implemented");
    }
}
