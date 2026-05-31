package com.anatomist.core.asmsolver;

import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Drives {@link AsmClassDeclaration} identity / kind / hierarchy from
 *  synthetic .class bytes — no external jars required. */
class AsmClassDeclarationTest {

    // ── identity ──

    @Test
    void identity_topLevel() {
        AsmClassDeclaration d = solve("com.x.Foo", classOf("com.x.Foo"));
        assertEquals("com.x.Foo", d.getQualifiedName());
        assertEquals("Foo", d.getName());
        assertEquals("com.x", d.getPackageName());
        assertEquals("Foo", d.getClassName());
    }

    @Test
    void identity_nestedClass_dollarSplit() {
        AsmClassDeclaration d = solve("com.x.Outer$Inner", classOf("com.x.Outer$Inner"));
        assertEquals("com.x.Outer$Inner", d.getQualifiedName());
        // Inner type name = portion after the LAST dollar
        assertEquals("Inner", d.getName());
        assertEquals("com.x", d.getPackageName());
        assertEquals("Outer$Inner", d.getClassName());
    }

    @Test
    void identity_defaultPackage() {
        AsmClassDeclaration d = solve("Foo", classOf("Foo"));
        assertEquals("Foo", d.getQualifiedName());
        assertEquals("Foo", d.getName());
        assertEquals("", d.getPackageName());
        assertEquals("Foo", d.getClassName());
    }

    // ── kind ──

    @Test
    void kind_regularClass() {
        AsmClassDeclaration d = solve("com.x.Foo", classOf("com.x.Foo"));
        assertTrue(d.isClass());
        assertFalse(d.isInterface());
        assertFalse(d.isEnum());
        assertFalse(d.isAnnotation());
        assertFalse(d.isTypeParameter());
        assertTrue(d.isType());
    }

    @Test
    void kind_interface() {
        AsmClassDeclaration d = solve("com.x.MyIface",
                interfaceBytes("com.x.MyIface"));
        assertTrue(d.isInterface());
        assertFalse(d.isClass());
    }

    @Test
    void kind_enum() {
        AsmClassDeclaration d = solve("com.x.MyEnum",
                enumBytes("com.x.MyEnum"));
        assertTrue(d.isEnum(), "ACC_ENUM bit must surface");
        assertFalse(d.isInterface());
    }

    @Test
    void kind_annotation() {
        AsmClassDeclaration d = solve("com.x.MyAnno",
                annotationBytes("com.x.MyAnno"));
        assertTrue(d.isAnnotation());
        // ACC_ANNOTATION implies ACC_INTERFACE in bytecode, but ResolvedTypeDeclaration
        // should report it as annotation only.
        assertFalse(d.isClass(), "annotation should not also report isClass");
    }

    // ── hierarchy ──

    @Test
    void hierarchy_superClass_resolvesAcrossSolver() {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put("com.x.Parent", classOf("com.x.Parent"));
        classes.put("com.x.Child", classWithSuper("com.x.Child", "com.x.Parent"));
        AsmTypeSolver solver = new AsmTypeSolver(new InMemoryClassFileSource(classes));
        wireWithJavaLang(solver);
        AsmClassDeclaration child = (AsmClassDeclaration)
                solver.tryToSolveType("com.x.Child").getCorrespondingDeclaration();
        assertTrue(child.getSuperClass().isPresent());
        assertEquals("com.x.Parent", child.getSuperClass().get().getQualifiedName());
    }

    @Test
    void hierarchy_interfaces_resolveAndDedupe() {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put("com.x.I1", interfaceBytes("com.x.I1"));
        classes.put("com.x.I2", interfaceBytes("com.x.I2"));
        classes.put("com.x.Impl", classWithInterfaces("com.x.Impl", "com.x.I1", "com.x.I2"));
        AsmTypeSolver solver = new AsmTypeSolver(new InMemoryClassFileSource(classes));
        wireWithJavaLang(solver);
        AsmClassDeclaration impl = (AsmClassDeclaration)
                solver.tryToSolveType("com.x.Impl").getCorrespondingDeclaration();
        Set<String> ifaces = impl.getInterfaces().stream()
                .map(ResolvedReferenceType::getQualifiedName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("com.x.I1", "com.x.I2"), ifaces);
    }

    @Test
    void hierarchy_objectHasNoSuperClass() {
        // java.lang.Object's super_name in the constant pool IS null. We model
        // that as Optional.empty().
        Map<String, byte[]> classes = new HashMap<>();
        // Hand-craft a bytecode where super = null
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/x/RootLike", null, null, null);
        cw.visitEnd();
        classes.put("com.x.RootLike", cw.toByteArray());
        AsmTypeSolver solver = new AsmTypeSolver(new InMemoryClassFileSource(classes));
        AsmClassDeclaration d = (AsmClassDeclaration)
                solver.tryToSolveType("com.x.RootLike").getCorrespondingDeclaration();
        assertTrue(d.getSuperClass().isEmpty());
    }

    @Test
    void hierarchy_getAncestors_returnsSuperAndInterfaces() {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put("com.x.Parent", classOf("com.x.Parent"));
        classes.put("com.x.I", interfaceBytes("com.x.I"));
        classes.put("com.x.C", classWithSuperAndInterfaces("com.x.C", "com.x.Parent", "com.x.I"));
        AsmTypeSolver solver = new AsmTypeSolver(new InMemoryClassFileSource(classes));
        wireWithJavaLang(solver);
        AsmClassDeclaration c = (AsmClassDeclaration)
                solver.tryToSolveType("com.x.C").getCorrespondingDeclaration();
        List<ResolvedReferenceType> anc = c.getAncestors(true);
        Set<String> fqns = anc.stream()
                .map(ResolvedReferenceType::getQualifiedName)
                .collect(Collectors.toSet());
        assertTrue(fqns.contains("com.x.Parent"), "ancestors must include super: " + fqns);
        assertTrue(fqns.contains("com.x.I"), "ancestors must include interface: " + fqns);
    }

    @Test
    void isAssignableBy_self_isTrue() {
        AsmClassDeclaration d = solve("com.x.Foo", classOf("com.x.Foo"));
        assertTrue(d.isAssignableBy(d));
    }

    @Test
    void isAssignableBy_subclass_isTrue() {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put("com.x.Parent", classOf("com.x.Parent"));
        classes.put("com.x.Child", classWithSuper("com.x.Child", "com.x.Parent"));
        AsmTypeSolver solver = new AsmTypeSolver(new InMemoryClassFileSource(classes));
        wireWithJavaLang(solver);
        AsmClassDeclaration parent = (AsmClassDeclaration)
                solver.tryToSolveType("com.x.Parent").getCorrespondingDeclaration();
        AsmClassDeclaration child = (AsmClassDeclaration)
                solver.tryToSolveType("com.x.Child").getCorrespondingDeclaration();
        assertTrue(parent.isAssignableBy(child), "Parent must accept Child");
        assertFalse(child.isAssignableBy(parent), "Child must NOT accept Parent");
    }

    // ── helpers ──

    static AsmClassDeclaration solve(String fqn, byte[] bytes) {
        AsmTypeSolver solver = new AsmTypeSolver(
                new InMemoryClassFileSource(Map.of(fqn, bytes)));
        return (AsmClassDeclaration) solver.tryToSolveType(fqn).getCorrespondingDeclaration();
    }

    /** Combine AsmTypeSolver with ReflectionTypeSolver for java.lang.Object resolution. */
    static void wireWithJavaLang(AsmTypeSolver solver) {
        CombinedTypeSolver combined = new CombinedTypeSolver();
        combined.add(solver);
        combined.add(new ReflectionTypeSolver());
    }

    static byte[] classOf(String fqn) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, fqn.replace('.', '/'),
                null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] classWithSuper(String fqn, String superFqn) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, fqn.replace('.', '/'),
                null, superFqn.replace('.', '/'), null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] classWithInterfaces(String fqn, String... ifaces) {
        String[] internal = new String[ifaces.length];
        for (int i = 0; i < ifaces.length; i++) internal[i] = ifaces[i].replace('.', '/');
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, fqn.replace('.', '/'),
                null, "java/lang/Object", internal);
        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] classWithSuperAndInterfaces(String fqn, String superFqn, String... ifaces) {
        String[] internal = new String[ifaces.length];
        for (int i = 0; i < ifaces.length; i++) internal[i] = ifaces[i].replace('.', '/');
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, fqn.replace('.', '/'),
                null, superFqn.replace('.', '/'), internal);
        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] interfaceBytes(String fqn) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                fqn.replace('.', '/'), null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] enumBytes(String fqn) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                fqn.replace('.', '/'), null, "java/lang/Enum", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] annotationBytes(String fqn) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION,
                fqn.replace('.', '/'),
                null, "java/lang/Object", new String[]{"java/lang/annotation/Annotation"});
        cw.visitEnd();
        return cw.toByteArray();
    }
}
