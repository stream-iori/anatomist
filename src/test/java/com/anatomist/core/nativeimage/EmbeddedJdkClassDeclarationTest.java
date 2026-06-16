package com.anatomist.core.nativeimage;

import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Pin {@link EmbeddedJdkClassDeclaration} behaviour against a synthetic
 *  {@link JdkTypeCatalog} so the test stays fast and deterministic. */
class EmbeddedJdkClassDeclarationTest {

    private static JdkTypeCatalog buildSmallCatalog() {
        JdkTypeCatalog cat = new JdkTypeCatalog(21);

        cat.add(new JdkType("java.lang.Object", null, List.of(),
                JdkType.FLAG_CLASS | JdkType.FLAG_PUBLIC, null, List.of(),
                List.of(
                        new JdkType.MethodEntry("toString", "()Ljava/lang/String;", JdkType.FLAG_PUBLIC),
                        new JdkType.MethodEntry("hashCode", "()I",                JdkType.FLAG_PUBLIC),
                        new JdkType.MethodEntry("equals",   "(Ljava/lang/Object;)Z", JdkType.FLAG_PUBLIC))));

        cat.add(new JdkType("java.lang.CharSequence", null, List.of(),
                JdkType.FLAG_INTERFACE | JdkType.FLAG_PUBLIC, null, List.of(),
                List.of(new JdkType.MethodEntry("length", "()I", JdkType.FLAG_PUBLIC | JdkType.FLAG_ABSTRACT))));

        cat.add(new JdkType("java.io.Serializable", null, List.of(),
                JdkType.FLAG_INTERFACE | JdkType.FLAG_PUBLIC, null, List.of(), List.of()));

        cat.add(new JdkType("java.lang.String", "java.lang.Object",
                List.of("java.lang.CharSequence", "java.io.Serializable"),
                JdkType.FLAG_CLASS | JdkType.FLAG_PUBLIC | JdkType.FLAG_FINAL, null,
                List.of(new JdkType.FieldEntry("CASE_INSENSITIVE_ORDER",
                        "Ljava/util/Comparator;",
                        JdkType.FLAG_STATIC | JdkType.FLAG_FINAL | JdkType.FLAG_PUBLIC)),
                List.of(
                        new JdkType.MethodEntry("length",    "()I",                       JdkType.FLAG_PUBLIC),
                        new JdkType.MethodEntry("substring", "(I)Ljava/lang/String;",     JdkType.FLAG_PUBLIC),
                        new JdkType.MethodEntry("substring", "(II)Ljava/lang/String;",    JdkType.FLAG_PUBLIC),
                        new JdkType.MethodEntry("charAt",    "(I)C",                      JdkType.FLAG_PUBLIC))));
        return cat;
    }

    // ── identity ──

    @Test
    void identity_basic() {
        EmbeddedJdkClassDeclaration d = decl("java.lang.String");
        assertEquals("java.lang.String", d.getQualifiedName());
        assertEquals("String", d.getName());
        assertEquals("java.lang", d.getPackageName());
    }

    // ── kind ──

    @Test
    void kind_class() {
        EmbeddedJdkClassDeclaration d = decl("java.lang.String");
        assertTrue(d.isClass());
        assertFalse(d.isInterface());
    }

    @Test
    void kind_interface() {
        EmbeddedJdkClassDeclaration d = decl("java.lang.CharSequence");
        assertTrue(d.isInterface());
        assertFalse(d.isClass());
    }

    // ── hierarchy ──

    @Test
    void hierarchy_superClass_resolvesViaCatalog() {
        EmbeddedJdkClassDeclaration s = decl("java.lang.String");
        assertTrue(s.getSuperClass().isPresent());
        assertEquals("java.lang.Object", s.getSuperClass().get().getQualifiedName());
    }

    @Test
    void hierarchy_interfaces_resolvedAndPresent() {
        EmbeddedJdkClassDeclaration s = decl("java.lang.String");
        Set<String> ifaces = s.getInterfaces().stream()
                .map(ResolvedReferenceType::getQualifiedName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("java.lang.CharSequence", "java.io.Serializable"), ifaces);
    }

    @Test
    void hierarchy_ancestors_includeAll() {
        EmbeddedJdkClassDeclaration s = decl("java.lang.String");
        Set<String> anc = s.getAncestors(true).stream()
                .map(ResolvedReferenceType::getQualifiedName)
                .collect(Collectors.toSet());
        assertTrue(anc.contains("java.lang.Object"));
        assertTrue(anc.contains("java.lang.CharSequence"));
        assertTrue(anc.contains("java.io.Serializable"));
    }

    // ── members ──

    @Test
    void declaredMethods_includeAllAndDistinguishOverloads() {
        EmbeddedJdkClassDeclaration s = decl("java.lang.String");
        Set<String> sigs = s.getDeclaredMethods().stream()
                .map(m -> m.getName() + ":" + m.getNumberOfParams())
                .collect(Collectors.toSet());
        assertTrue(sigs.contains("length:0"));
        assertTrue(sigs.contains("charAt:1"));
        assertTrue(sigs.contains("substring:1"));
        assertTrue(sigs.contains("substring:2"));
    }

    @Test
    void declaredFields_carryDescriptorBackedTypes() {
        EmbeddedJdkClassDeclaration s = decl("java.lang.String");
        assertEquals(1, s.getDeclaredFields().size());
        var f = s.getDeclaredFields().iterator().next();
        assertEquals("CASE_INSENSITIVE_ORDER", f.getName());
        assertTrue(f.isStatic());
    }

    // ── isAssignableBy ──

    @Test
    void isAssignableBy_self() {
        EmbeddedJdkClassDeclaration s = decl("java.lang.String");
        assertTrue(s.isAssignableBy(s));
    }

    @Test
    void isAssignableBy_interfaceFromImpl() {
        EmbeddedJdkClassDeclaration cs = decl("java.lang.CharSequence");
        EmbeddedJdkClassDeclaration str = decl("java.lang.String");
        assertTrue(cs.isAssignableBy(str), "CharSequence accepts String");
    }

    // ── helpers ──

    private static EmbeddedJdkClassDeclaration decl(String fqn) {
        EmbeddedJdkTypeSolver solver = new EmbeddedJdkTypeSolver(buildSmallCatalog());
        CombinedTypeSolver combined = new CombinedTypeSolver();
        combined.add(solver);
        ResolvedReferenceTypeDeclaration r =
                solver.tryToSolveType(fqn).getCorrespondingDeclaration();
        assertInstanceOf(EmbeddedJdkClassDeclaration.class, r);
        return (EmbeddedJdkClassDeclaration) r;
    }
}
