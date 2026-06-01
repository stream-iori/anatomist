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

        // java.lang.Object — root, no super, no interfaces
        JdkType obj = new JdkType();
        obj.fqn = "java.lang.Object";
        obj.superFqn = null;
        obj.interfaceFqns = List.of();
        obj.flags = JdkType.FLAG_CLASS | JdkType.FLAG_PUBLIC;
        obj.fields = List.of();
        obj.methods = List.of(
                new JdkType.MethodEntry("toString", "()Ljava/lang/String;", JdkType.FLAG_PUBLIC),
                new JdkType.MethodEntry("hashCode", "()I",                JdkType.FLAG_PUBLIC),
                new JdkType.MethodEntry("equals",   "(Ljava/lang/Object;)Z", JdkType.FLAG_PUBLIC));
        cat.add(obj);

        // java.lang.CharSequence — interface
        JdkType cs = new JdkType();
        cs.fqn = "java.lang.CharSequence";
        cs.superFqn = null;
        cs.interfaceFqns = List.of();
        cs.flags = JdkType.FLAG_INTERFACE | JdkType.FLAG_PUBLIC;
        cs.fields = List.of();
        cs.methods = List.of(
                new JdkType.MethodEntry("length", "()I", JdkType.FLAG_PUBLIC | JdkType.FLAG_ABSTRACT));
        cat.add(cs);

        // java.io.Serializable — marker interface
        JdkType ser = new JdkType();
        ser.fqn = "java.io.Serializable";
        ser.superFqn = null;
        ser.interfaceFqns = List.of();
        ser.flags = JdkType.FLAG_INTERFACE | JdkType.FLAG_PUBLIC;
        ser.fields = List.of();
        ser.methods = List.of();
        cat.add(ser);

        // java.lang.String — extends Object, implements CharSequence + Serializable
        JdkType str = new JdkType();
        str.fqn = "java.lang.String";
        str.superFqn = "java.lang.Object";
        str.interfaceFqns = List.of("java.lang.CharSequence", "java.io.Serializable");
        str.flags = JdkType.FLAG_CLASS | JdkType.FLAG_PUBLIC | JdkType.FLAG_FINAL;
        str.fields = List.of(
                new JdkType.FieldEntry("CASE_INSENSITIVE_ORDER",
                        "Ljava/util/Comparator;",
                        JdkType.FLAG_STATIC | JdkType.FLAG_FINAL | JdkType.FLAG_PUBLIC));
        str.methods = List.of(
                new JdkType.MethodEntry("length",    "()I",                       JdkType.FLAG_PUBLIC),
                new JdkType.MethodEntry("substring", "(I)Ljava/lang/String;",     JdkType.FLAG_PUBLIC),
                new JdkType.MethodEntry("substring", "(II)Ljava/lang/String;",    JdkType.FLAG_PUBLIC),
                new JdkType.MethodEntry("charAt",    "(I)C",                      JdkType.FLAG_PUBLIC));
        cat.add(str);
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
