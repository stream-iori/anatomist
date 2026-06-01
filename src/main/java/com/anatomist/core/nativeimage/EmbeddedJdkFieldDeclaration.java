package com.anatomist.core.nativeimage;

import com.anatomist.core.asmsolver.AsmDescriptorParser;
import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.Optional;

/** Catalog-backed {@link ResolvedFieldDeclaration}. */
public class EmbeddedJdkFieldDeclaration implements ResolvedFieldDeclaration {

    private final JdkType.FieldEntry entry;
    private final EmbeddedJdkClassDeclaration declaring;
    private final TypeSolver solver;

    EmbeddedJdkFieldDeclaration(JdkType.FieldEntry entry,
                                EmbeddedJdkClassDeclaration declaring,
                                TypeSolver solver) {
        this.entry = entry;
        this.declaring = declaring;
        this.solver = solver;
    }

    @Override
    public String getName() { return entry.name; }

    @Override
    public ResolvedType getType() {
        return AsmDescriptorParser.parseFieldDescriptor(entry.descriptor, solver);
    }

    @Override
    public boolean isStatic() { return (entry.flags & JdkType.FLAG_STATIC) != 0; }

    @Override
    public boolean isVolatile() { return false; }

    @Override
    public ResolvedTypeDeclaration declaringType() { return declaring; }

    @Override
    public AccessSpecifier accessSpecifier() {
        if ((entry.flags & JdkType.FLAG_PUBLIC) != 0) return AccessSpecifier.PUBLIC;
        return AccessSpecifier.NONE;
    }

    @Override
    public Optional<com.github.javaparser.ast.Node> toAst() { return Optional.empty(); }
}
