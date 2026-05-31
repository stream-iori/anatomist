package com.anatomist.core.asmsolver;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import org.objectweb.asm.Opcodes;

import java.util.Collections;
import java.util.List;

/** ASM-backed {@link ResolvedConstructorDeclaration}. */
public class AsmConstructorDeclaration implements ResolvedConstructorDeclaration {

    private final String descriptor;
    private final int access;
    private final AsmClassDeclaration declaring;
    private final TypeSolver solver;
    private volatile List<ResolvedType> paramTypes;

    AsmConstructorDeclaration(String descriptor, int access,
                              AsmClassDeclaration declaring, TypeSolver solver) {
        this.descriptor = descriptor;
        this.access = access;
        this.declaring = declaring;
        this.solver = solver;
    }

    @Override
    public ResolvedReferenceTypeDeclaration declaringType() { return declaring; }

    @Override
    public String getName() { return declaring.getName(); }

    @Override
    public int getNumberOfParams() { return params().size(); }

    @Override
    public ResolvedParameterDeclaration getParam(int i) {
        return new AsmParameterDeclaration(params().get(i), i, this);
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
}
