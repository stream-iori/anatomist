package com.anatomist.core.asmsolver;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.Optional;

public class AsmFieldDeclaration implements ResolvedFieldDeclaration {

    private final String name;
    private final String descriptor;
    private final int access;
    private final AsmClassDeclaration declaring;

    AsmFieldDeclaration(String name, String descriptor, int access,
                        AsmClassDeclaration declaring) {
        this.name = name;
        this.descriptor = descriptor;
        this.access = access;
        this.declaring = declaring;
    }

    @Override
    public String getName() { return name; }

    @Override
    public ResolvedType getType() {
        return AsmDescriptorParser.parseFieldDescriptor(descriptor, declaring.solver());
    }

    @Override
    public boolean isStatic() { return AccessFlags.isStatic(access); }

    @Override
    public boolean isVolatile() { return (access & 0x0040) != 0; }

    @Override
    public ResolvedTypeDeclaration declaringType() { return declaring; }

    @Override
    public Optional<com.github.javaparser.ast.Node> toAst() { return Optional.empty(); }

    public AccessSpecifier accessSpecifier() {
        return AccessFlags.toSpecifier(access);
    }

    String descriptor() { return descriptor; }
    int access() { return access; }

    @Override
    public String toString() {
        return "AsmFieldDeclaration(" + declaring.getQualifiedName() + "#" + name + ")";
    }
}
