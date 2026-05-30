package com.anatomist.extract;

import com.anatomist.model.ExtractionResult;
import com.github.javaparser.ast.CompilationUnit;

/**
 * Per-file extractor contract. Implementations receive a JavaParser
 * {@link CompilationUnit} (with its {@code SymbolResolver} already attached)
 * and append nodes / edges / annotations into {@code result}.
 *
 * <p>Symbol resolution failures ({@code UnsolvedSymbolException},
 * {@code UnsupportedOperationException}) should be caught per-site and
 * counted via {@code ExtractionContext.incrementUnresolved()} rather than
 * aborting the whole file.</p>
 */
public interface Extractor {

    void extract(CompilationUnit unit, ExtractionResult result);
}
