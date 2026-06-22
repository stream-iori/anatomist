package com.anatomist.framework;

import com.anatomist.model.ExtractionResult;
import com.github.javaparser.ast.CompilationUnit;

public interface JavaAstAnalyzer {
    String id();
    void analyze(CompilationUnit unit, ExtractionResult result);
}
