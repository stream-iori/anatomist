package com.anatomist.config;

import java.util.*;

public class ProjectConfig {

    private int javaVersion = 8;
    private List<String> exclude = List.of();
    private boolean includeTests = false;
    private boolean springXml = false;
    private boolean vmClasspath = true;

    private List<String> externalExcludePatterns = List.of(
            "java.lang.*",
            "java.io.*",
            "java.net.*",
            "java.nio.*",
            "java.math.*",
            "sun.*",
            "com.sun.*",
            "jdk.*"
    );

    public int javaVersion() { return javaVersion; }
    public void setJavaVersion(int v) { this.javaVersion = v; }

    public List<String> exclude() { return exclude; }
    public void setExclude(List<String> v) { this.exclude = List.copyOf(v); }

    public boolean includeTests() { return includeTests; }
    public void setIncludeTests(boolean v) { this.includeTests = v; }

    public boolean springXml() { return springXml; }
    public void setSpringXml(boolean v) { this.springXml = v; }

    public boolean vmClasspath() { return vmClasspath; }
    public void setVmClasspath(boolean v) { this.vmClasspath = v; }

    public List<String> externalExcludePatterns() { return externalExcludePatterns; }
    public void setExternalExcludePatterns(List<String> v) { this.externalExcludePatterns = List.copyOf(v); }

    public void addExternalExcludePatterns(List<String> extra) {
        var merged = new ArrayList<>(externalExcludePatterns);
        for (String p : extra) {
            if (!merged.contains(p)) merged.add(p);
        }
        this.externalExcludePatterns = List.copyOf(merged);
    }

    public boolean isExternalExcluded(String fqn) {
        if (fqn == null) return true;
        for (String pattern : externalExcludePatterns) {
            if (matchesPattern(pattern, fqn)) return true;
        }
        return false;
    }

    static boolean matchesPattern(String pattern, String fqn) {
        if (pattern.endsWith(".*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return fqn.startsWith(prefix);
        }
        if (pattern.endsWith(".**")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return fqn.startsWith(prefix);
        }
        return fqn.equals(pattern);
    }
}
