package com.anatomist.framework;

import java.util.List;

/** Framework-neutral analyzer collection assembled by application adapters. */
public record AnalyzerRegistry(
        List<JavaAstAnalyzer> javaAstAnalyzers,
        List<ProjectAnalyzer> projectAnalyzers
) {
    public AnalyzerRegistry {
        javaAstAnalyzers = javaAstAnalyzers == null ? List.of() : List.copyOf(javaAstAnalyzers);
        projectAnalyzers = projectAnalyzers == null ? List.of() : List.copyOf(projectAnalyzers);
    }
}
