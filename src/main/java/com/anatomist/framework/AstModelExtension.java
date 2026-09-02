package com.anatomist.framework;

import com.github.javaparser.ast.CompilationUnit;

/** Runs after parsing and before contract hashing/core extraction. Implementations must be idempotent. */
public interface AstModelExtension extends ExtensionPoint {
    void augment(CompilationUnit unit);
}
