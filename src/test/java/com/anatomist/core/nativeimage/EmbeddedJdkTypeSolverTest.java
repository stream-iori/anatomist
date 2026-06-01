package com.anatomist.core.nativeimage;

import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.TypeSolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedJdkTypeSolverTest {

    @Test
    void unknownFqn_returnsUnsolved() {
        EmbeddedJdkTypeSolver solver = new EmbeddedJdkTypeSolver(new JdkTypeCatalog(21));
        SymbolReference<ResolvedReferenceTypeDeclaration> ref =
                solver.tryToSolveType("java.lang.String");
        assertFalse(ref.isSolved());
    }

    @Test
    void knownFqn_returnsSolved() {
        JdkTypeCatalog cat = new JdkTypeCatalog(21);
        JdkType t = new JdkType();
        t.fqn = "java.lang.Object";
        t.interfaceFqns = List.of();
        t.flags = JdkType.FLAG_CLASS;
        t.fields = List.of();
        t.methods = List.of();
        cat.add(t);
        EmbeddedJdkTypeSolver solver = new EmbeddedJdkTypeSolver(cat);
        SymbolReference<ResolvedReferenceTypeDeclaration> ref =
                solver.tryToSolveType("java.lang.Object");
        assertTrue(ref.isSolved());
        assertEquals("java.lang.Object", ref.getCorrespondingDeclaration().getQualifiedName());
    }

    @Test
    void cachesResolvedDeclarations() {
        JdkTypeCatalog cat = new JdkTypeCatalog(21);
        JdkType t = new JdkType();
        t.fqn = "java.lang.Object"; t.interfaceFqns = List.of();
        t.flags = JdkType.FLAG_CLASS; t.fields = List.of(); t.methods = List.of();
        cat.add(t);
        EmbeddedJdkTypeSolver solver = new EmbeddedJdkTypeSolver(cat);
        var a = solver.tryToSolveType("java.lang.Object").getCorrespondingDeclaration();
        var b = solver.tryToSolveType("java.lang.Object").getCorrespondingDeclaration();
        assertSame(a, b, "same instance must be returned on repeated lookup");
    }

    @Test
    void parentWiring_works() {
        EmbeddedJdkTypeSolver solver = new EmbeddedJdkTypeSolver(new JdkTypeCatalog(21));
        TypeSolver fake = new com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver();
        solver.setParent(fake);
        assertSame(fake, solver.getParent());
        assertSame(fake, solver.getRoot());
    }
}
