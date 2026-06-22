package com.anatomist.framework;

import com.anatomist.model.ExtractionResult;

public interface ProjectAnalyzer {
    String id();
    boolean enabled(AnalysisContext context);
    void analyze(AnalysisContext context, ExtractionResult result);
}
