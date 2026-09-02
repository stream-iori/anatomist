package com.anatomist.framework;

import com.anatomist.model.ExtractionResult;
import com.github.javaparser.ast.CompilationUnit;

/** Emits structural facts from one already augmented Java compilation unit. */
public interface JavaUnitAnalyzer extends ExtensionPoint {
    void analyze(CompilationUnit unit, ExtractionResult result);
}
