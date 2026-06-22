package com.anatomist.framework;

import com.anatomist.framework.spring.SpringComponentAnalyzer;
import com.anatomist.framework.spring.SpringMvcAnalyzer;
import com.anatomist.framework.spring.SpringXmlAnalyzer;

import java.util.List;

public final class AnalyzerRegistry {

    private AnalyzerRegistry() {}

    public static List<JavaAstAnalyzer> javaAstAnalyzers(AnalysisContext context) {
        return List.of(
                new SpringComponentAnalyzer(context.extractionContext()),
                new SpringMvcAnalyzer(context.extractionContext())
        );
    }

    public static List<ProjectAnalyzer> projectAnalyzers() {
        return List.of(new SpringXmlAnalyzer());
    }
}
