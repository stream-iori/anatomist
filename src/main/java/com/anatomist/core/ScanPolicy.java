package com.anatomist.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Canonical file-eligibility policy shared by full and incremental indexing. */
public final class ScanPolicy {
    public static final String HARD_EXCLUDE_POLICY_VERSION = "v1";

    private final Path projectRoot;
    private final List<String> includes;
    private final List<String> excludes;
    private final List<PathGlob> includeGlobs;
    private final List<PathGlob> excludeGlobs;
    private final Set<String> excludedDirNames;

    public ScanPolicy(Path projectRoot, List<String> includes, List<String> excludes,
                      Set<String> excludedDirNames) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.includes = normalized(includes == null || includes.isEmpty() ? List.of("**") : includes);
        this.excludes = normalized(excludes == null ? List.of() : excludes);
        this.includeGlobs = this.includes.stream().map(PathGlob::new).toList();
        this.excludeGlobs = this.excludes.stream().map(PathGlob::new).toList();
        this.excludedDirNames = excludedDirNames == null ? Set.of() : Set.copyOf(excludedDirNames);
    }

    public static ScanPolicy all(Path projectRoot) {
        return new ScanPolicy(projectRoot, List.of("**"), List.of(), Set.of());
    }

    public boolean includes(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        String candidate;
        try {
            candidate = projectRoot.relativize(absolute).toString().replace('\\', '/');
        } catch (IllegalArgumentException ex) {
            candidate = absolute.toString().replace('\\', '/');
        }
        if (containsExcludedDir(candidate)) return false;
        String logicalPath = candidate;
        boolean included = includeGlobs.stream().anyMatch(glob -> glob.matches(logicalPath));
        return included && excludeGlobs.stream().noneMatch(glob -> glob.matches(logicalPath));
    }

    public List<String> includes() { return includes; }
    public List<String> excludes() { return excludes; }
    public Set<String> excludedDirNames() { return excludedDirNames; }

    public String canonical(List<SourceRoot> roots, List<SourceScope> scopes) {
        StringBuilder out = new StringBuilder("anatomist-scan-policy-v1\n");
        out.append("hard=").append(HARD_EXCLUDE_POLICY_VERSION).append('\n');
        for (SourceScope scope : scopes.stream().sorted().toList()) {
            out.append("scope=").append(scope).append('\n');
        }
        for (SourceRoot root : roots.stream()
                .sorted(Comparator.comparing(r -> r.module() + "@" + r.scope() + "=" + r.path()))
                .toList()) {
            out.append("root=").append(root.module()).append('@').append(root.scope())
                    .append('=').append(root.path().toAbsolutePath().normalize()).append('\n');
        }
        includes.forEach(value -> out.append("include=").append(value).append('\n'));
        excludes.forEach(value -> out.append("exclude=").append(value).append('\n'));
        excludedDirNames.stream().sorted().forEach(value -> out.append("exclude-dir=").append(value).append('\n'));
        return out.toString();
    }

    public String fingerprint(List<SourceRoot> roots, List<SourceScope> scopes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical(roots, scopes).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean containsExcludedDir(String candidate) {
        if (excludedDirNames.isEmpty()) return false;
        for (String part : candidate.split("/")) {
            if (excludedDirNames.contains(part)) return true;
        }
        return false;
    }

    private static List<String> normalized(List<String> values) {
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) unique.add(value.replace('\\', '/'));
        List<String> out = new ArrayList<>(unique);
        out.sort(String::compareTo);
        return List.copyOf(out);
    }
}
