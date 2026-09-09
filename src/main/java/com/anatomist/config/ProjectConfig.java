package com.anatomist.config;

import com.anatomist.core.SourceScope;

import java.util.*;

public class ProjectConfig {

    private List<String> captureIncludeIgnored = List.of();
    private boolean versionsAutoGc, versionsIncludeCaches;
    private int versionsKeep = 20, versionsMaxAgeDays = 30;
    private long versionsMaxBytes;
    public List<String> captureIncludeIgnored() { return captureIncludeIgnored; }
    public void setCaptureIncludeIgnored(List<String> value) { captureIncludeIgnored=List.copyOf(value); }
    public boolean versionsAutoGc() { return versionsAutoGc; }
    public void setVersionsAutoGc(boolean value) { versionsAutoGc=value; }
    public boolean versionsIncludeCaches() { return versionsIncludeCaches; }
    public void setVersionsIncludeCaches(boolean value) { versionsIncludeCaches=value; }
    public int versionsKeep() { return versionsKeep; }
    public void setVersionsKeep(int value) { if(value<0) throw new IllegalArgumentException("keep must be >= 0"); versionsKeep=value; }
    public int versionsMaxAgeDays() { return versionsMaxAgeDays; }
    public void setVersionsMaxAgeDays(int value) { if(value<0) throw new IllegalArgumentException("max_age_days must be >= 0"); versionsMaxAgeDays=value; }
    public long versionsMaxBytes() { return versionsMaxBytes; }
    public void setVersionsMaxBytes(long value) { if(value<0) throw new IllegalArgumentException("max_bytes must be >= 0"); versionsMaxBytes=value; }

    private Integer javaVersion;
    private boolean springXml = false;
    private boolean vmClasspath = true;
    private com.anatomist.framework.lombok.LombokMode lombokMode =
            com.anatomist.framework.lombok.LombokMode.OFF;
    private boolean lombokStrict;

    private List<SourceScope> scanScopes = List.of(SourceScope.MAIN, SourceScope.GENERATED);
    private boolean scanScopesConfigured;
    private List<String> scanIncludes = List.of("**");
    private List<String> scanExcludes = List.of();
    private List<String> sourceRootSpecs = List.of();

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

    public Integer javaVersion() { return javaVersion; }
    public boolean hasJavaVersion() { return javaVersion != null; }
    public void setJavaVersion(int v) { this.javaVersion = v; }

    public boolean springXml() { return springXml; }
    public void setSpringXml(boolean v) { this.springXml = v; }

    public boolean vmClasspath() { return vmClasspath; }
    public void setVmClasspath(boolean v) { this.vmClasspath = v; }

    public com.anatomist.framework.lombok.LombokMode lombokMode() { return lombokMode; }
    public void setLombokMode(String value) {
        this.lombokMode = com.anatomist.framework.lombok.LombokMode.parse(value);
    }
    public boolean lombokStrict() { return lombokStrict; }
    public void setLombokStrict(boolean value) { this.lombokStrict = value; }

    public List<SourceScope> scanScopes() { return scanScopes; }
    public boolean scanScopesConfigured() { return scanScopesConfigured; }
    public void setScanScopes(List<SourceScope> value) {
        this.scanScopes = value == null ? List.of() : List.copyOf(value);
        this.scanScopesConfigured = true;
    }

    public List<String> scanIncludes() { return scanIncludes; }
    public void setScanIncludes(List<String> value) {
        this.scanIncludes = value == null || value.isEmpty() ? List.of("**") : List.copyOf(value);
    }

    public List<String> scanExcludes() { return scanExcludes; }
    public void setScanExcludes(List<String> value) {
        this.scanExcludes = value == null ? List.of() : List.copyOf(value);
    }

    public List<String> sourceRootSpecs() { return sourceRootSpecs; }
    public void setSourceRootSpecs(List<String> value) {
        this.sourceRootSpecs = value == null ? List.of() : List.copyOf(value);
    }

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
