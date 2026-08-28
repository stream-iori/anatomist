package com.anatomist.config;

import com.anatomist.core.SourceScope;

import java.util.*;

public class ProjectConfig {

    private Integer javaVersion;
    private boolean springXml = false;
    private boolean vmClasspath = true;
    private boolean dataflow = false;
    private String dataflowMode;
    private List<String> dataflowScopes = List.of();
    private boolean implicitTaint = false;

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

    public boolean dataflow() { return dataflow; }
    public void setDataflow(boolean value) { this.dataflow = value; }

    public String dataflowMode() { return dataflowMode; }
    public void setDataflowMode(String value) { this.dataflowMode = value; }

    public List<String> dataflowScopes() { return dataflowScopes; }
    public void setDataflowScopes(List<String> value) {
        this.dataflowScopes = value == null ? List.of() : List.copyOf(value);
    }

    public boolean implicitTaint() { return implicitTaint; }
    public void setImplicitTaint(boolean value) { this.implicitTaint = value; }

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
