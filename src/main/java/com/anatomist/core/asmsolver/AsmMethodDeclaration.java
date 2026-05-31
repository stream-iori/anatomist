package com.anatomist.core.asmsolver;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import org.objectweb.asm.Opcodes;

import java.util.Collections;
import java.util.List;

/** ASM-backed {@link ResolvedMethodDeclaration}. Erased-only — generic
 *  type parameters and the {@code Signature} attribute are deferred. */
public class AsmMethodDeclaration implements ResolvedMethodDeclaration {

    private final String name;
    private final String descriptor;
    private final int access;
    private final AsmClassDeclaration declaring;
    private final TypeSolver solver;

    // Parsed lazily — descriptor parsing involves solver lookups which we
    // don't want to do until the caller actually probes the signature.
    private volatile List<ResolvedType> paramTypes;
    private volatile ResolvedType returnType;

    AsmMethodDeclaration(String name, String descriptor, int access,
                         AsmClassDeclaration declaring, TypeSolver solver) {
        this.name = name;
        this.descriptor = descriptor;
        this.access = access;
        this.declaring = declaring;
        this.solver = solver;
    }

    @Override
    public String getName() { return name; }

    @Override
    public ResolvedReferenceTypeDeclaration declaringType() { return declaring; }

    @Override
    public int getNumberOfParams() { return params().size(); }

    @Override
    public ResolvedParameterDeclaration getParam(int i) {
        return new AsmParameterDeclaration(params().get(i), i, this);
    }

    @Override
    public ResolvedType getReturnType() {
        if (returnType == null) {
            synchronized (this) {
                if (returnType == null) {
                    returnType = AsmDescriptorParser.parseMethodReturnType(descriptor, solver);
                }
            }
        }
        return returnType;
    }

    @Override
    public boolean isAbstract() {
        return (access & Opcodes.ACC_ABSTRACT) != 0;
    }

    @Override
    public boolean isStatic() {
        return (access & Opcodes.ACC_STATIC) != 0;
    }

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
        return Collections.emptyList();
    }

    @Override
    public String toDescriptor() { return descriptor; }

    public AccessSpecifier accessSpecifier() {
        if ((access & Opcodes.ACC_PUBLIC) != 0)    return AccessSpecifier.PUBLIC;
        if ((access & Opcodes.ACC_PROTECTED) != 0) return AccessSpecifier.PROTECTED;
        if ((access & Opcodes.ACC_PRIVATE) != 0)   return AccessSpecifier.PRIVATE;
        return AccessSpecifier.NONE;
    }

    private List<ResolvedType> params() {
        if (paramTypes == null) {
            synchronized (this) {
                if (paramTypes == null) {
                    paramTypes = AsmDescriptorParser.parseMethodParameters(descriptor, solver);
                }
            }
        }
        return paramTypes;
    }

    @Override
    public String toString() {
        return "AsmMethodDeclaration(" + declaring.getQualifiedName() + "#" + name + descriptor + ")";
    }
}
