package com.anatomist.core.asmsolver;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AsmTypeSolverTest {

    @Test
    void unknownFqn_returnsUnsolved() {
        AsmTypeSolver solver = new AsmTypeSolver(new InMemoryClassFileSource(Map.of()));
        SymbolReference<ResolvedReferenceTypeDeclaration> ref =
                solver.tryToSolveType("does.not.Exist");
        assertNotNull(ref);
        assertFalse(ref.isSolved());
    }

    @Test
    void knownFqn_returnsSolvedReference() {
        byte[] bytes = miniClass("com.x.Foo");
        AsmTypeSolver solver = new AsmTypeSolver(
                new InMemoryClassFileSource(Map.of("com.x.Foo", bytes)));
        SymbolReference<ResolvedReferenceTypeDeclaration> ref =
                solver.tryToSolveType("com.x.Foo");
        assertTrue(ref.isSolved(), "expected solved reference");
        assertEquals("com.x.Foo", ref.getCorrespondingDeclaration().getQualifiedName());
    }

    @Test
    void cachesResolvedDeclarations() {
        AsmTypeSolver solver = new AsmTypeSolver(
                new InMemoryClassFileSource(Map.of("com.x.Foo", miniClass("com.x.Foo"))));
        var r1 = solver.tryToSolveType("com.x.Foo").getCorrespondingDeclaration();
        var r2 = solver.tryToSolveType("com.x.Foo").getCorrespondingDeclaration();
        assertSame(r1, r2, "same instance must be returned on repeated lookup");
    }

    @Test
    void setParent_andGetParent_areWired() {
        AsmTypeSolver child = new AsmTypeSolver(new InMemoryClassFileSource(Map.of()));
        com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver fakeParent =
                new com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver();
        child.setParent(fakeParent);
        assertSame(fakeParent, child.getParent());
    }

    @Test
    void getRoot_walksParentChain() {
        AsmTypeSolver leaf = new AsmTypeSolver(new InMemoryClassFileSource(Map.of()));
        com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver root =
                new com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver();
        leaf.setParent(root);
        assertSame(root, leaf.getRoot());
    }

    private static byte[] miniClass(String fqn) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, fqn.replace('.', '/'),
                null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
