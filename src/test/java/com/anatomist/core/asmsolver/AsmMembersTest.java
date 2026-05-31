package com.anatomist.core.asmsolver;

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

class AsmMembersTest {

    @Test
    void declaredFields_arePresent_withTypes() {
        Map<String, byte[]> classes = Map.of(
                "com.x.Foo", classWithFields("com.x.Foo",
                        new Field("name", "Ljava/lang/String;", Opcodes.ACC_PRIVATE),
                        new Field("age",  "I",                 Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)));
        AsmClassDeclaration d = solve(classes, "com.x.Foo");
        List<? extends com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration> fields =
                d.getDeclaredFields();
        assertEquals(2, fields.size());

        var byName = fields.stream().collect(Collectors.toMap(
                com.github.javaparser.resolution.declarations.ResolvedDeclaration::getName, f -> f));
        var name = byName.get("name");
        var age = byName.get("age");
        assertNotNull(name); assertNotNull(age);
        assertEquals("java.lang.String", name.getType().describe());
        assertEquals("int", age.getType().describe());
        assertTrue(age.isStatic());
        assertFalse(name.isStatic());
        assertEquals(d, age.declaringType());
    }

    @Test
    void declaredMethods_distinguishOverloads() {
        Map<String, byte[]> classes = Map.of(
                "com.x.Foo", classWithMethods("com.x.Foo",
                        new Method("substring", "(I)Ljava/lang/String;",  Opcodes.ACC_PUBLIC),
                        new Method("substring", "(II)Ljava/lang/String;", Opcodes.ACC_PUBLIC),
                        new Method("length",    "()I",                    Opcodes.ACC_PUBLIC)));
        AsmClassDeclaration d = solve(classes, "com.x.Foo");
        Set<? extends com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration> methods =
                d.getDeclaredMethods();
        assertEquals(3, methods.size());
        long substring = methods.stream().filter(m -> m.getName().equals("substring")).count();
        assertEquals(2, substring, "two overloads of substring");
        var length = methods.stream().filter(m -> m.getName().equals("length")).findFirst().orElseThrow();
        assertEquals(0, length.getNumberOfParams());
        assertEquals("int", length.getReturnType().describe());
    }

    @Test
    void method_parameterTypesResolveAcrossSolver() {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put("com.x.Bar", classOf("com.x.Bar"));
        classes.put("com.x.Foo", classWithMethods("com.x.Foo",
                new Method("acceptBar", "(Lcom/x/Bar;)V", Opcodes.ACC_PUBLIC)));
        AsmClassDeclaration d = solve(classes, "com.x.Foo");
        var m = d.getDeclaredMethods().iterator().next();
        assertEquals("acceptBar", m.getName());
        assertEquals(1, m.getNumberOfParams());
        assertEquals("com.x.Bar", m.getParam(0).getType().describe());
        assertEquals("void", m.getReturnType().describe());
        assertTrue(d.isAssignableBy(d), "self-assignable sanity");
    }

    @Test
    void constructorList_separateFromMethods() {
        Map<String, byte[]> classes = Map.of(
                "com.x.Foo", classWithMethods("com.x.Foo",
                        new Method("<init>", "()V", Opcodes.ACC_PUBLIC),
                        new Method("<init>", "(I)V", Opcodes.ACC_PUBLIC),
                        new Method("foo",    "()V", Opcodes.ACC_PUBLIC)));
        AsmClassDeclaration d = solve(classes, "com.x.Foo");
        // <init> entries should populate getConstructors, NOT getDeclaredMethods
        assertEquals(2, d.getConstructors().size());
        assertEquals(1, d.getDeclaredMethods().size());
        assertEquals("foo", d.getDeclaredMethods().iterator().next().getName());
    }

    @Test
    void abstractAndStaticFlags_surface() {
        Map<String, byte[]> classes = Map.of(
                "com.x.Foo", classWithMethods("com.x.Foo",
                        new Method("statique", "()V", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC),
                        new Method("abstrait", "()V", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT)));
        AsmClassDeclaration d = solve(classes, "com.x.Foo");
        var byName = d.getDeclaredMethods().stream().collect(Collectors.toMap(
                com.github.javaparser.resolution.declarations.ResolvedDeclaration::getName, m -> m));
        assertTrue(byName.get("statique").isStatic());
        assertTrue(byName.get("abstrait").isAbstract());
        assertFalse(byName.get("statique").isAbstract());
        assertFalse(byName.get("abstrait").isStatic());
    }

    // ── helpers ──

    record Field(String name, String desc, int access) {}
    record Method(String name, String desc, int access) {}

    static AsmClassDeclaration solve(Map<String, byte[]> classes, String fqn) {
        AsmTypeSolver solver = new AsmTypeSolver(new InMemoryClassFileSource(classes));
        CombinedTypeSolver combined = new CombinedTypeSolver();
        combined.add(solver);
        combined.add(new ReflectionTypeSolver());
        return (AsmClassDeclaration) solver.tryToSolveType(fqn).getCorrespondingDeclaration();
    }

    static byte[] classOf(String fqn) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, fqn.replace('.', '/'),
                null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] classWithFields(String fqn, Field... fields) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, fqn.replace('.', '/'),
                null, "java/lang/Object", null);
        for (Field f : fields) cw.visitField(f.access, f.name, f.desc, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] classWithMethods(String fqn, Method... methods) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, fqn.replace('.', '/'),
                null, "java/lang/Object", null);
        for (Method m : methods) cw.visitMethod(m.access, m.name, m.desc, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
