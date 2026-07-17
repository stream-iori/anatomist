package com.anatomist.core;

import java.util.Map;

public record IndexResult(
        Map<String, Long> kindCounts,
        Map<String, Long> relationCounts,
        long annotationCount,
        long semanticAnnotationCount,
        int fileCacheSize,
        long unresolvedCount,
        int droppedDanglingEdges,
        long elapsedMs,
        boolean springXml,
        Map<String, Object> unresolvedSamples,
        boolean samplingEnabled,
        ParseInventory parseInventory,
        java.util.List<IndexDiagnostic> diagnostics,
        int flowNodes,
        int flowEdges,
        int flowSummaries
) {}
