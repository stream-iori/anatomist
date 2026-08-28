package com.anatomist.application;

import java.nio.file.Path;
import java.util.List;

/** Structured Watch-to-index invocation; deliberately independent of Picocli. */
public record IndexExecutionRequest(
        Path projectPath,
        String projectSource,
        List<String> sourceRootSpecs,
        boolean includeTests,
        List<String> scanScopeSpecs,
        List<String> scanIncludeSpecs,
        List<String> scanExcludeSpecs,
        boolean noClasspath,
        String classpath,
        boolean vmClasspath,
        Integer javaVersion,
        Path jdkHome,
        Path output,
        boolean incremental,
        boolean springXml,
        boolean dataflow,
        String dataflowMode,
        List<String> dataflowScopes,
        boolean implicitTaint,
        boolean strictHealth,
        String healthPolicy,
        boolean timings,
        int maxRealignFiles,
        Path operationLockPath,
        IndexExecutionHints executionHints,
        boolean deferFullFallback
) {
    public IndexExecutionRequest {
        sourceRootSpecs = sourceRootSpecs == null ? List.of() : List.copyOf(sourceRootSpecs);
        scanScopeSpecs = scanScopeSpecs == null ? List.of() : List.copyOf(scanScopeSpecs);
        scanIncludeSpecs = scanIncludeSpecs == null ? List.of() : List.copyOf(scanIncludeSpecs);
        scanExcludeSpecs = scanExcludeSpecs == null ? List.of() : List.copyOf(scanExcludeSpecs);
        dataflowScopes = dataflowScopes == null ? List.of() : List.copyOf(dataflowScopes);
    }
}
