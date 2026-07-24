package com.anatomist.core.nativeimage;

import com.github.javaparser.resolution.declarations.ResolvedEnumConstantDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;

/** Enum constant backed by a catalog field carrying {@link JdkType#FLAG_ENUM}. */
final class EmbeddedJdkEnumConstantDeclaration implements ResolvedEnumConstantDeclaration {

    private final EmbeddedJdkFieldDeclaration field;

    EmbeddedJdkEnumConstantDeclaration(EmbeddedJdkFieldDeclaration field) {
        this.field = field;
    }

    @Override
    public String getName() { return field.getName(); }

    @Override
    public ResolvedType getType() { return field.getType(); }
}
