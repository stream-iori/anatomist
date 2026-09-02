package com.anatomist.framework;

import com.github.javaparser.ast.CompilationUnit;

/** Runs after parsing and before contract hashing/core extraction. Implementations must be idempotent. */
public interface AstModelExtension extends ExtensionPoint {
    /** Cheap, side-effect-free guard used to avoid cloning unrelated units. */
    default boolean appliesTo(CompilationUnit unit) {
        return true;
    }

    void augment(CompilationUnit unit);
}
