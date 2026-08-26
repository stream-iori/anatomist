package com.anatomist.application;

import java.nio.file.Path;
import java.util.List;

/** Structured Watch-to-index invocation; deliberately independent of Picocli. */
public record IndexExecutionRequest(
        Path projectPath,
        String projectSource,
        List<String> sourceRootSpecs,
        boolean includeTests,
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
        dataflowScopes = dataflowScopes == null ? List.of() : List.copyOf(dataflowScopes);
    }
}
