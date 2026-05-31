package com.anatomist.core.nativeimage;

import com.anatomist.core.asmsolver.AsmDescriptorParser;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.Collections;
import java.util.List;

/** Catalog-backed {@link ResolvedMethodDeclaration}. Mirrors
 *  {@link com.anatomist.core.asmsolver.AsmMethodDeclaration} — both translate
 *  JVM method descriptors via {@link AsmDescriptorParser}. */
public class EmbeddedJdkMethodDeclaration implements ResolvedMethodDeclaration {

    private final JdkType.MethodEntry entry;
    private final EmbeddedJdkClassDeclaration declaring;
    private final TypeSolver solver;

    private volatile List<ResolvedType> paramTypes;
    private volatile ResolvedType returnType;

    EmbeddedJdkMethodDeclaration(JdkType.MethodEntry entry,
                                 EmbeddedJdkClassDeclaration declaring,
                                 TypeSolver solver) {
        this.entry = entry;
        this.declaring = declaring;
        this.solver = solver;
    }

    @Override
    public String getName() { return entry.name; }

    @Override
    public ResolvedReferenceTypeDeclaration declaringType() { return declaring; }

    @Override
    public int getNumberOfParams() { return params().size(); }

    @Override
    public ResolvedParameterDeclaration getParam(int i) {
        return new EmbeddedJdkParameterDeclaration(params().get(i), i);
    }

    @Override
    public ResolvedType getReturnType() {
        if (returnType == null) {
            synchronized (this) {
                if (returnType == null) {
                    returnType = AsmDescriptorParser.parseMethodReturnType(entry.descriptor, solver);
                }
            }
        }
        return returnType;
    }

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
        return Collections.emptyList();
    }

    @Override
    public String toDescriptor() { return entry.descriptor; }

    @Override
    public com.github.javaparser.ast.AccessSpecifier accessSpecifier() {
        if ((entry.flags & JdkType.FLAG_PUBLIC) != 0) return com.github.javaparser.ast.AccessSpecifier.PUBLIC;
        return com.github.javaparser.ast.AccessSpecifier.NONE;
    }

    private List<ResolvedType> params() {
        if (paramTypes == null) {
            synchronized (this) {
                if (paramTypes == null) {
                    paramTypes = AsmDescriptorParser.parseMethodParameters(entry.descriptor, solver);
                }
            }
        }
        return paramTypes;
    }
}
