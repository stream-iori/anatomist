package com.anatomist.core;

import com.anatomist.store.FileCacheService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/** Compatibility fingerprint for inputs that can change Java symbol resolution. */
public final class IndexEnvironmentFingerprint {

    public static final String META_KEY = "index_environment_hash";
    public static final String CLASSPATH_ARTIFACTS_KEY = "classpath_artifacts_hash";

    private IndexEnvironmentFingerprint() {}

    public static Snapshot snapshot(List<SourceRoot> sourceRoots,
                                    int javaVersion,
                                    String classpathMode,
                                    List<Path> classpathEntries,
                                    String classpathOverride,
                                    boolean springXml) {
        return snapshot(sourceRoots, javaVersion, classpathMode, classpathEntries,
                classpathOverride, springXml, false, false);
    }

    public static Snapshot snapshot(List<SourceRoot> sourceRoots,
                                    int javaVersion,
                                    String classpathMode,
                                    List<Path> classpathEntries,
                                    String classpathOverride,
                                    boolean springXml,
                                    boolean dataflow,
                                    boolean implicitTaint) {
        String layout = sourceLayout(sourceRoots);
        String artifacts = classpathArtifacts(classpathEntries);
        String canonical = "anatomist-index-environment-v1\n"
                + "layout=" + layout + "\n"
                + "java=" + javaVersion + "\n"
                + "mode=" + safe(classpathMode) + "\n"
                + "override=" + safe(classpathOverride) + "\n"
                + "springXml=" + springXml + "\n"
                + "dataflow=" + dataflow + "\n"
                + "implicitTaint=" + implicitTaint + "\n"
                + "artifacts=" + artifacts + "\n";
        return new Snapshot(
                FileCacheService.sha256OfString(canonical),
                FileCacheService.sha256OfString(layout),
                FileCacheService.sha256OfString(artifacts));
    }

    /** Stable fingerprint of resolved classpath entries, independent of build-file bytes. */
    public static String classpathArtifactsHash(List<Path> classpathEntries) {
        return FileCacheService.sha256OfString(classpathArtifacts(classpathEntries));
    }

    private static String sourceLayout(List<SourceRoot> roots) {
        if (roots == null || roots.isEmpty()) return "";
        return roots.stream()
                .map(root -> root.module() + "@" + root.scope() + "="
                        + root.path().toAbsolutePath().normalize())
                .sorted()
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String classpathArtifacts(List<Path> entries) {
        if (entries == null || entries.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (Path supplied : entries) {
            if (supplied == null) continue;
            Path path = supplied.toAbsolutePath().normalize();
            out.append(path);
            try {
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                out.append('|').append(attrs.isDirectory() ? "dir" : "file")
                        .append('|').append(attrs.size())
                        .append('|').append(attrs.lastModifiedTime().toMillis());
            } catch (IOException ex) {
                out.append("|missing");
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record Snapshot(String hash, String sourceLayoutHash, String classpathArtifactsHash) {}
}
