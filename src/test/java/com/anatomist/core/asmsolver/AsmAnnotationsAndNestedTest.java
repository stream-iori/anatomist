package com.anatomist.core.asmsolver;

import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AsmAnnotationsAndNestedTest {

    @Test
    void directAnnotation_recognised() {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put("com.x.MyAnno", annotationBytes("com.x.MyAnno"));
        classes.put("com.x.Annotated", classWithAnnotation("com.x.Annotated", "Lcom/x/MyAnno;"));
        AsmClassDeclaration d = solve(classes, "com.x.Annotated");
        assertTrue(d.hasDirectlyAnnotation("com.x.MyAnno"),
                "@MyAnno should be detected as directly annotated");
        assertFalse(d.hasDirectlyAnnotation("com.x.Other"));
    }

    @Test
    void noAnnotation_returnsFalse() {
        AsmClassDeclaration d = solve(Map.of("com.x.Foo", classOf("com.x.Foo")), "com.x.Foo");
        assertFalse(d.hasDirectlyAnnotation("com.x.Anything"));
    }

    @Test
    void nestedTypes_internalTypesPopulated() {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put("com.x.Outer", classWithInnerRef("com.x.Outer", "com/x/Outer$Inner"));
        classes.put("com.x.Outer$Inner", classOf("com.x.Outer$Inner"));
        AsmClassDeclaration outer = solve(classes, "com.x.Outer");
        var nested = outer.internalTypes();
        assertEquals(1, nested.size(), "expected one internal type");
        assertEquals("com.x.Outer$Inner", nested.iterator().next().getQualifiedName());
        assertTrue(outer.hasInternalType("Inner"));
        assertEquals("com.x.Outer$Inner",
                outer.getInternalType("Inner").getQualifiedName());
    }

    @Test
    void nestedTypes_emptyWhenNone() {
        AsmClassDeclaration d = solve(Map.of("com.x.Foo", classOf("com.x.Foo")), "com.x.Foo");
        assertTrue(d.internalTypes().isEmpty());
        assertFalse(d.hasInternalType("Anything"));
    }

    // ── helpers ──

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

    static byte[] annotationBytes(String fqn) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION,
                fqn.replace('.', '/'),
                null, "java/lang/Object", new String[]{"java/lang/annotation/Annotation"});
        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] classWithAnnotation(String fqn, String annoDescriptor) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, fqn.replace('.', '/'),
                null, "java/lang/Object", null);
        AnnotationVisitor av = cw.visitAnnotation(annoDescriptor, /*visible=*/true);
        if (av != null) av.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] classWithInnerRef(String fqn, String innerInternal) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, fqn.replace('.', '/'),
                null, "java/lang/Object", null);
        cw.visitInnerClass(innerInternal, fqn.replace('.', '/'), "Inner",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
