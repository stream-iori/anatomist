package com.anatomist.flow;

public record FlowNode(
        String id,
        String methodId,
        String kind,
        String label,
        String sourceFile,
        String module,
        String scope,
        int line,
        int column,
        String calleeMethod,
        String slot,
        String metadata
) {}
