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
        JdkType t = new JdkType("java.lang.Object", null, List.of(),
                JdkType.FLAG_CLASS, null, List.of(), List.of());
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
        JdkType t = new JdkType("java.lang.Object", null, List.of(),
                JdkType.FLAG_CLASS, null, List.of(), List.of());
        cat.add(t);
        EmbeddedJdkTypeSolver solver = new EmbeddedJdkTypeSolver(cat);
        var a = solver.tryToSolveType("java.lang.Object").getCorrespondingDeclaration();
        var b = solver.tryToSolveType("java.lang.Object").getCorrespondingDeclaration();
        assertSame(a, b, "same instance must be returned on repeated lookup");
    }

    @Test
    void sourceStyleNestedTypeNameResolvesToBinaryCatalogEntry() {
        JdkTypeCatalog cat = new JdkTypeCatalog(25);
        cat.add(new JdkType("java.util.Map$Entry", null, List.of(),
                JdkType.FLAG_INTERFACE | JdkType.FLAG_PUBLIC, null, List.of(), List.of()));
        EmbeddedJdkTypeSolver solver = new EmbeddedJdkTypeSolver(cat);

        var sourceName = solver.tryToSolveType("java.util.Map.Entry");
        var binaryName = solver.tryToSolveType("java.util.Map$Entry");

        assertTrue(sourceName.isSolved());
        assertSame(sourceName.getCorrespondingDeclaration(), binaryName.getCorrespondingDeclaration());
        assertEquals("java.util.Map$Entry",
                sourceName.getCorrespondingDeclaration().getQualifiedName());
    }

    @Test
    void parentWiring_works() {
        EmbeddedJdkTypeSolver solver = new EmbeddedJdkTypeSolver(new JdkTypeCatalog(21));
        TypeSolver fake = new com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver();
        solver.setParent(fake);
        assertSame(fake, solver.getParent());
        assertSame(fake, solver.getRoot());
    }

    @Test
    void methodVarargsAndEnumConstantsSurfaceFromCatalogFlags() {
        JdkTypeCatalog cat = new JdkTypeCatalog(25);
        cat.add(new JdkType("p.Api", "java.lang.Object", List.of(),
                JdkType.FLAG_CLASS, null,
                List.of(), List.of(new JdkType.MethodEntry(
                        "join", "(I[I)V", JdkType.FLAG_PUBLIC | JdkType.FLAG_VARARGS))));
        cat.add(new JdkType("p.Mode", "java.lang.Enum", List.of(),
                JdkType.FLAG_ENUM | JdkType.FLAG_PUBLIC, null,
                List.of(new JdkType.FieldEntry(
                        "A", "Lp/Mode;", JdkType.FLAG_ENUM | JdkType.FLAG_PUBLIC)), List.of()));
        EmbeddedJdkTypeSolver solver = new EmbeddedJdkTypeSolver(cat);

        var api = solver.solveType("p.Api");
        var method = api.getDeclaredMethods().iterator().next();
        assertFalse(method.getParam(0).isVariadic());
        assertTrue(method.getParam(1).isVariadic());

        var mode = solver.solveType("p.Mode");
        assertSame(mode, mode.asEnum());
        assertEquals(List.of("A"), mode.asEnum().getEnumConstants().stream()
                .map(constant -> constant.getName()).toList());
    }
}
