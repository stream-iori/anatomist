package com.anatomist.flow;

public record MethodFlowCoverage(
        String methodId,
        String sourceFile,
        String detailLevel
) {}
