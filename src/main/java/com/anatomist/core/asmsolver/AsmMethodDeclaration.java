package com.anatomist.core.asmsolver;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.Collections;
import java.util.List;

public class AsmMethodDeclaration implements ResolvedMethodDeclaration {

    private final String name;
    private final int access;
    private final AsmClassDeclaration declaring;
    private final LazyDescriptorResolver resolver;

    AsmMethodDeclaration(String name, int access,
                         AsmClassDeclaration declaring,
                         LazyDescriptorResolver resolver) {
        this.name = name;
        this.access = access;
        this.declaring = declaring;
        this.resolver = resolver;
    }

    @Override
    public String getName() { return name; }

    @Override
    public ResolvedReferenceTypeDeclaration declaringType() { return declaring; }

    @Override
    public int getNumberOfParams() { return resolver.paramTypes().size(); }

    @Override
    public ResolvedParameterDeclaration getParam(int i) {
        return new AsmParameterDeclaration(resolver.paramTypes().get(i), i, this);
    }

    @Override
    public ResolvedType getReturnType() { return resolver.returnType(); }

    @Override
    public boolean isAbstract() { return AccessFlags.isAbstract(access); }

    @Override
    public boolean isStatic() { return AccessFlags.isStatic(access); }

    @Override
    public boolean isDefaultMethod() {
        return declaring.isInterface() && !isStatic() && !isAbstract();
    }

    @Override
    public int getNumberOfSpecifiedExceptions() { return 0; }

    @Override
    public ResolvedType getSpecifiedException(int i) {
        throw new IndexOutOfBoundsException("no exceptions recorded");
    }

    @Override
    public List<ResolvedTypeParameterDeclaration> getTypeParameters() {
        if (resolver.signature() == null) return Collections.emptyList();
        return AsmSignatureParser.parseMethodTypeParameters(
                resolver.signature(), declaring.getQualifiedName(), resolver.solver());
    }

    @Override
    public String toDescriptor() { return resolver.descriptor(); }

    public AccessSpecifier accessSpecifier() {
        return AccessFlags.toSpecifier(access);
    }

    @Override
    public String toString() {
        return "AsmMethodDeclaration(" + declaring.getQualifiedName() + "#" + name + resolver.descriptor() + ")";
    }
}
