package com.anatomist.framework;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.ExtractionContext;

import java.nio.file.Path;
import java.util.List;

public record AnalysisContext(
        Path projectRoot,
        List<Path> sourcePaths,
        ExtractionContext extractionContext,
        ProjectConfig config,
        boolean springXml
) {}
