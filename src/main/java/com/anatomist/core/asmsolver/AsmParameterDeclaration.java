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
        int access = owner instanceof AsmMethodDeclaration method ? method.access()
                : owner instanceof AsmConstructorDeclaration constructor ? constructor.access()
                : 0;
        return index == owner.getNumberOfParams() - 1 && AccessFlags.isVarArgs(access);
    }

    @Override
    public boolean isParameter() {
        return true;
    }
}
