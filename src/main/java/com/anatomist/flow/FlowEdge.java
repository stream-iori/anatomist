package com.anatomist.flow;

public record FlowEdge(
        String sourceNode,
        String targetNode,
        String relation,
        String methodId,
        String sourceFile,
        String confidence,
        String context,
        String metadata
) {}
