package com.anatomist.core.nativeimage;

import com.anatomist.core.asmsolver.AsmSignatureParser;
import com.anatomist.core.asmsolver.LazyDescriptorResolver;
import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.Collections;
import java.util.List;

public class EmbeddedJdkMethodDeclaration implements ResolvedMethodDeclaration {

    private final JdkType.MethodEntry entry;
    private final EmbeddedJdkClassDeclaration declaring;
    private final LazyDescriptorResolver resolver;

    EmbeddedJdkMethodDeclaration(JdkType.MethodEntry entry,
                                 EmbeddedJdkClassDeclaration declaring,
                                 TypeSolver solver) {
        this.entry = entry;
        this.declaring = declaring;
        this.resolver = new LazyDescriptorResolver(entry.signature, entry.descriptor, solver);
    }

    @Override
    public String getName() { return entry.name; }

    @Override
    public ResolvedReferenceTypeDeclaration declaringType() { return declaring; }

    @Override
    public int getNumberOfParams() { return resolver.paramTypes().size(); }

    @Override
    public ResolvedParameterDeclaration getParam(int i) {
        boolean variadic = i == getNumberOfParams() - 1
                && (entry.flags & JdkType.FLAG_VARARGS) != 0;
        return new EmbeddedJdkParameterDeclaration(resolver.paramTypes().get(i), i, variadic);
    }

    @Override
    public ResolvedType getReturnType() { return resolver.returnType(); }

    @Override
    public boolean isAbstract() { return (entry.flags & JdkType.FLAG_ABSTRACT) != 0; }

    @Override
    public boolean isStatic() { return (entry.flags & JdkType.FLAG_STATIC) != 0; }

    @Override
    public boolean isDefaultMethod() {
        return declaring.isInterface() && !isStatic() && !isAbstract();
    }

    @Override
    public int getNumberOfSpecifiedExceptions() { return 0; }

    @Override
    public ResolvedType getSpecifiedException(int i) {
        throw new IndexOutOfBoundsException();
    }

    @Override
    public List<ResolvedTypeParameterDeclaration> getTypeParameters() {
        if (entry.signature == null) return Collections.emptyList();
        return AsmSignatureParser.parseMethodTypeParameters(
                entry.signature, declaring.getQualifiedName(), resolver.solver());
    }

    @Override
    public String toDescriptor() { return entry.descriptor; }

    @Override
    public AccessSpecifier accessSpecifier() {
        if ((entry.flags & JdkType.FLAG_PUBLIC) != 0) return AccessSpecifier.PUBLIC;
        return AccessSpecifier.NONE;
    }
}
