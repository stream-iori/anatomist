package com.anatomist.core.asmsolver;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import org.objectweb.asm.Opcodes;

import java.util.Optional;

/** ASM-backed {@link ResolvedFieldDeclaration}. */
public class AsmFieldDeclaration implements ResolvedFieldDeclaration {

    private final String name;
    private final String descriptor;
    private final int access;
    private final AsmClassDeclaration declaring;
    private final TypeSolver solver;

    AsmFieldDeclaration(String name, String descriptor, int access,
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
    public ResolvedType getType() {
        return AsmDescriptorParser.parseFieldDescriptor(descriptor, solver);
    }

    @Override
    public boolean isStatic() {
        return (access & Opcodes.ACC_STATIC) != 0;
    }

    @Override
    public boolean isVolatile() {
        return (access & Opcodes.ACC_VOLATILE) != 0;
    }

    @Override
    public ResolvedTypeDeclaration declaringType() {
        return declaring;
    }

    @Override
    public Optional<com.github.javaparser.ast.Node> toAst() {
        return Optional.empty();
    }

    public AccessSpecifier accessSpecifier() {
        if ((access & Opcodes.ACC_PUBLIC) != 0)    return AccessSpecifier.PUBLIC;
        if ((access & Opcodes.ACC_PROTECTED) != 0) return AccessSpecifier.PROTECTED;
        if ((access & Opcodes.ACC_PRIVATE) != 0)   return AccessSpecifier.PRIVATE;
        return AccessSpecifier.NONE;
    }

    @Override
    public String toString() {
        return "AsmFieldDeclaration(" + declaring.getQualifiedName() + "#" + name + ")";
    }
}
