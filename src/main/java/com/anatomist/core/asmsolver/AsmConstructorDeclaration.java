package com.anatomist.core.asmsolver;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.Collections;
import java.util.List;

public class AsmConstructorDeclaration implements ResolvedConstructorDeclaration {

    private final int access;
    private final AsmClassDeclaration declaring;
    private final LazyDescriptorResolver resolver;

    AsmConstructorDeclaration(String descriptor, int access,
                              AsmClassDeclaration declaring) {
        this.access = access;
        this.declaring = declaring;
        this.resolver = new LazyDescriptorResolver(descriptor, declaring.solver());
    }

    @Override
    public ResolvedReferenceTypeDeclaration declaringType() { return declaring; }

    @Override
    public String getName() { return declaring.getName(); }

    @Override
    public int getNumberOfParams() { return resolver.paramTypes().size(); }

    @Override
    public ResolvedParameterDeclaration getParam(int i) {
        return new AsmParameterDeclaration(resolver.paramTypes().get(i), i, this);
    }

    @Override
    public int getNumberOfSpecifiedExceptions() { return 0; }

    @Override
    public ResolvedType getSpecifiedException(int i) {
        throw new IndexOutOfBoundsException();
    }

    @Override
    public List<ResolvedTypeParameterDeclaration> getTypeParameters() {
        return Collections.emptyList();
    }

    public AccessSpecifier accessSpecifier() {
        return AccessFlags.toSpecifier(access);
    }

    int access() { return access; }
    String descriptor() { return resolver.descriptor(); }
}
