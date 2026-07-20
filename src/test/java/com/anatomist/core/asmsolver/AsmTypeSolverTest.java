package com.anatomist.core.asmsolver;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.Map;
import java.util.LinkedHashMap;

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
    void sourceNestedName_resolvesBinaryNestedClass() {
        AsmTypeSolver solver = new AsmTypeSolver(new InMemoryClassFileSource(Map.of(
                "com.x.Outer$Inner$Deep", miniClass("com.x.Outer$Inner$Deep"))));

        var resolved = solver.tryToSolveType("com.x.Outer.Inner.Deep");

        assertTrue(resolved.isSolved());
        assertEquals("com.x.Outer$Inner$Deep",
                resolved.getCorrespondingDeclaration().getQualifiedName());
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
    void declarationCacheIsBounded() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put("com.x.A", miniClass("com.x.A"));
        classes.put("com.x.B", miniClass("com.x.B"));
        classes.put("com.x.C", miniClass("com.x.C"));
        AsmTypeSolver solver = new AsmTypeSolver(new InMemoryClassFileSource(classes), 512);

        assertTrue(solver.tryToSolveType("com.x.A").isSolved());
        assertTrue(solver.tryToSolveType("com.x.B").isSolved());
        assertTrue(solver.tryToSolveType("com.x.C").isSolved());

        assertTrue(solver.cachedTypeCount() <= 1,
                "declaration cache must honor its maximum weight");
        assertTrue(solver.tryToSolveType("com.x.A").isSolved(),
                "an evicted declaration must remain resolvable from class bytes");
    }

    @Test
    void parsedDeclarationReleasesRawClassBytes() {
        AsmTypeSolver solver = new AsmTypeSolver(
                new InMemoryClassFileSource(Map.of("com.x.Foo", miniClass("com.x.Foo"))));
        AsmClassDeclaration declaration = (AsmClassDeclaration) solver
                .tryToSolveType("com.x.Foo").getCorrespondingDeclaration();

        assertFalse(declaration.rawBytesReleased());
        assertTrue(declaration.isClass());
        assertTrue(declaration.rawBytesReleased());
        assertEquals("com.x.Foo", declaration.getQualifiedName());
    }

    @Test
    void rejectsNonPositiveCacheSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new AsmTypeSolver(new InMemoryClassFileSource(Map.of()), 0));
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
