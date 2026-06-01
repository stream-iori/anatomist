package com.anatomist.core.nativeimage;

import com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;

/** Lightweight parameter declaration (no parameter names — catalog only
 *  carries erased descriptors). */
final class EmbeddedJdkParameterDeclaration implements ResolvedParameterDeclaration {

    private final ResolvedType type;
    private final int index;

    EmbeddedJdkParameterDeclaration(ResolvedType type, int index) {
        this.type = type;
        this.index = index;
    }

    @Override
    public String getName() { return "arg" + index; }

    @Override
    public ResolvedType getType() { return type; }

    @Override
    public boolean isVariadic() { return false; }

    @Override
    public boolean isParameter() { return true; }
}
