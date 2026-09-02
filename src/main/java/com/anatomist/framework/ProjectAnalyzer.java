package com.anatomist.framework;

import com.anatomist.model.ExtractionResult;

/** @deprecated use {@link ProjectResourceAnalyzer}. */
@Deprecated(forRemoval = false)
public interface ProjectAnalyzer extends ExtensionPoint {
    boolean enabled(AnalysisContext context);
    void analyze(AnalysisContext context, ExtractionResult result);
}
