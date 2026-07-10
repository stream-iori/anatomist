package com.anatomist.extract;

import com.github.javaparser.ast.CompilationUnit;

/** Single source of truth for the file that produced an extracted fact. */
final class SourceFiles {

    private SourceFiles() {}

    static String of(CompilationUnit unit) {
        if (unit == null) return null;
        if (unit.containsData(TypeExtractor.SourceFileKey.KEY)) {
            return unit.getData(TypeExtractor.SourceFileKey.KEY);
        }
        return unit.getStorage().map(s -> s.getPath().toString()).orElse(null);
    }
}
