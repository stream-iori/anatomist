package com.anatomist.flow;

public record MethodFlowSummary(
        String methodId,
        String inputSlot,
        String outputSlot,
        String relation,
        String sourceFile,
        String confidence,
        String metadata
) {}
