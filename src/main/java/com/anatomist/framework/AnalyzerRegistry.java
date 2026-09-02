package com.anatomist.framework;

import java.util.List;

/** Framework-neutral analyzer collection assembled by application adapters. */
public record AnalyzerRegistry(
        List<AstModelExtension> astModelExtensions,
        List<JavaUnitAnalyzer> javaUnitAnalyzers,
        List<ProjectResourceProvider> projectResourceProviders,
        List<ProjectResourceAnalyzer> projectResourceAnalyzers
) {
    public AnalyzerRegistry {
        astModelExtensions = astModelExtensions == null ? List.of() : List.copyOf(astModelExtensions);
        javaUnitAnalyzers = javaUnitAnalyzers == null ? List.of() : List.copyOf(javaUnitAnalyzers);
        projectResourceProviders = projectResourceProviders == null ? List.of() : List.copyOf(projectResourceProviders);
        projectResourceAnalyzers = projectResourceAnalyzers == null ? List.of() : List.copyOf(projectResourceAnalyzers);
    }

    public AnalyzerRegistry(List<AstModelExtension> astModelExtensions,
                            List<JavaUnitAnalyzer> javaUnitAnalyzers,
                            List<ProjectResourceAnalyzer> projectResourceAnalyzers) {
        this(astModelExtensions, javaUnitAnalyzers, List.of(), projectResourceAnalyzers);
    }

    /** Compatibility constructor for existing internal callers. */
    public AnalyzerRegistry(List<? extends JavaUnitAnalyzer> javaAnalyzers,
                            List<? extends ProjectResourceAnalyzer> projectAnalyzers) {
        this(List.of(), javaAnalyzers == null ? List.of() : List.copyOf(javaAnalyzers), List.of(),
                projectAnalyzers == null ? List.of() : List.copyOf(projectAnalyzers));
    }

    /** Compatibility accessor retained while callers migrate. */
    public List<JavaUnitAnalyzer> javaAstAnalyzers() { return javaUnitAnalyzers; }
}
