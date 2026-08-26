package com.anatomist.framework.spring;

import com.anatomist.framework.AnalysisContext;
import com.anatomist.framework.AnalyzerRegistry;

import java.util.List;

/** Spring adapter composition kept outside the framework-neutral SPI package. */
public final class SpringAnalyzers {

    private SpringAnalyzers() {}

    public static AnalyzerRegistry registry(AnalysisContext context) {
        return new AnalyzerRegistry(
                List.of(
                        new SpringComponentAnalyzer(context.extractionContext()),
                        new SpringMvcAnalyzer(context.extractionContext())),
                List.of(new SpringXmlAnalyzer()));
    }
}
