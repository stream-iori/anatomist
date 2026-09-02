package com.anatomist.framework.spring;

import com.anatomist.framework.AnalysisContext;
import com.anatomist.framework.AnalyzerRegistry;

import java.util.List;

/** Spring adapter composition kept outside the framework-neutral SPI package. */
public final class SpringAnalyzers {

    private SpringAnalyzers() {}

    public static AnalyzerRegistry registry(AnalysisContext context) {
        com.anatomist.core.ExtractionContext extractionContext =
                context == null ? null : context.extractionContext();
        return new AnalyzerRegistry(
                List.of(),
                List.of(
                        new SpringComponentAnalyzer(extractionContext),
                        new SpringMvcAnalyzer(extractionContext)),
                List.of(new SpringXmlResourceProvider()),
                List.of(new SpringXmlAnalyzer()));
    }
}
