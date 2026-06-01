package com.anatomist.core.asmsolver;

import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;

/** Lightweight {@link ResolvedParameterDeclaration} — no name (jars don't
 *  ship parameter names unless built with {@code -parameters}; anatomist's
 *  call-graph generator only needs the type for erased signatures). */
final class AsmParameterDeclaration implements ResolvedParameterDeclaration {

    private final ResolvedType type;
    private final int index;
    private final ResolvedMethodLikeDeclaration owner;

    AsmParameterDeclaration(ResolvedType type, int index, ResolvedMethodLikeDeclaration owner) {
        this.type = type;
        this.index = index;
        this.owner = owner;
    }

    @Override
    public String getName() {
        return "arg" + index;
    }

    @Override
    public ResolvedType getType() {
        return type;
    }

    @Override
    public boolean isVariadic() {
        return false; // ACC_VARARGS lives on the method, not on the descriptor
    }

    @Override
    public boolean isParameter() {
        return true;
    }
}
