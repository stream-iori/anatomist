package com.anatomist.core.asmsolver;

import com.github.javaparser.resolution.declarations.ResolvedEnumConstantDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;

/** Enum constant backed by an ASM field carrying {@code ACC_ENUM}. */
final class AsmEnumConstantDeclaration implements ResolvedEnumConstantDeclaration {

    private final AsmFieldDeclaration field;

    AsmEnumConstantDeclaration(AsmFieldDeclaration field) {
        this.field = field;
    }

    @Override
    public String getName() { return field.getName(); }

    @Override
    public ResolvedType getType() { return field.getType(); }
}
