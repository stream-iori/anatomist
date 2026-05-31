package com.anatomist.core.asmsolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Pin {@link ClassFileSource} behaviour for both in-memory and jar-backed
 *  variants. Keeps the rest of the AsmTypeSolver stack testable without
 *  needing real third-party jars on disk. */
class ClassFileSourceTest {

    @Test
    void inMemory_returnsBytesForKnownFqn() {
        byte[] bytes = miniClass("com.x.Foo");
        ClassFileSource src = new InMemoryClassFileSource(java.util.Map.of("com.x.Foo", bytes));
        Optional<byte[]> got = src.find("com.x.Foo");
        assertTrue(got.isPresent());
        assertArrayEquals(bytes, got.get());
    }

    @Test
    void inMemory_emptyForUnknown() {
        ClassFileSource src = new InMemoryClassFileSource(java.util.Map.of());
        assertTrue(src.find("does.not.Exist").isEmpty());
    }

    @Test
    void inMemory_indexesKnownClasses() {
        ClassFileSource src = new InMemoryClassFileSource(java.util.Map.of(
                "com.x.A", miniClass("com.x.A"),
                "com.x.B", miniClass("com.x.B")));
        assertEquals(java.util.Set.of("com.x.A", "com.x.B"), src.knownClasses());
    }

    @Test
    void jarBacked_readsClassFromJar(@TempDir Path tmp) throws Exception {
        Path jarPath = tmp.resolve("test.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jar.putNextEntry(new JarEntry("com/x/Foo.class"));
            jar.write(miniClass("com.x.Foo"));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("com/x/Bar.class"));
            jar.write(miniClass("com.x.Bar"));
            jar.closeEntry();
        }
        try (ClassFileSource src = new JarClassFileSource(jarPath)) {
            assertEquals(java.util.Set.of("com.x.Foo", "com.x.Bar"), src.knownClasses());
            assertTrue(src.find("com.x.Foo").isPresent());
            assertTrue(src.find("com.x.Bar").isPresent());
            assertTrue(src.find("com.x.Missing").isEmpty());
            // Bytes must round-trip
            assertArrayEquals(miniClass("com.x.Foo"), src.find("com.x.Foo").get());
        }
    }

    @Test
    void jarBacked_ignoresNonClassEntries(@TempDir Path tmp) throws Exception {
        Path jarPath = tmp.resolve("test.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jar.putNextEntry(new JarEntry("README.md"));
            jar.write("hello".getBytes());
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            jar.write("Manifest-Version: 1.0\n".getBytes());
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("com/x/Foo.class"));
            jar.write(miniClass("com.x.Foo"));
            jar.closeEntry();
        }
        try (ClassFileSource src = new JarClassFileSource(jarPath)) {
            assertEquals(java.util.Set.of("com.x.Foo"), src.knownClasses());
        }
    }

    /** Produce a minimal valid class file. */
    static byte[] miniClass(String fqn) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, fqn.replace('.', '/'),
                null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
