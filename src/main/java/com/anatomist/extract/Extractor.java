package com.anatomist.extract;

import com.anatomist.model.ExtractionResult;
import org.eclipse.jdt.core.dom.CompilationUnit;

public interface Extractor {

    void extract(CompilationUnit unit, ExtractionResult result);
}
