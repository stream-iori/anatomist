package com.anatomist.core.nativeimage;

import com.anatomist.core.asmsolver.LazyDescriptorResolver;
import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.Collections;
import java.util.List;

final class EmbeddedJdkConstructorDeclaration implements ResolvedConstructorDeclaration {

    private final JdkType.MethodEntry entry;
    private final EmbeddedJdkClassDeclaration declaring;
    private final LazyDescriptorResolver resolver;

    EmbeddedJdkConstructorDeclaration(JdkType.MethodEntry entry,
                                       EmbeddedJdkClassDeclaration declaring,
                                       TypeSolver solver) {
        this.entry = entry;
        this.declaring = declaring;
        this.resolver = new LazyDescriptorResolver(entry.descriptor, solver);
    }

    @Override
    public ResolvedReferenceTypeDeclaration declaringType() { return declaring; }

    @Override
    public String getName() { return declaring.getName(); }

    @Override
    public int getNumberOfParams() { return resolver.paramTypes().size(); }

    @Override
    public ResolvedParameterDeclaration getParam(int i) {
        return new EmbeddedJdkParameterDeclaration(resolver.paramTypes().get(i), i);
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

    @Override
    public AccessSpecifier accessSpecifier() {
        if ((entry.flags & JdkType.FLAG_PUBLIC) != 0) return AccessSpecifier.PUBLIC;
        return AccessSpecifier.NONE;
    }
}
