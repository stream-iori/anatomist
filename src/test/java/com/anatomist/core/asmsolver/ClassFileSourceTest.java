package com.anatomist.core.asmsolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;
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

    @Test
    void classpathSource_readsDirectoriesAndJarsWithFirstEntryWins(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes/com/x"));
        byte[] directoryFoo = miniClass("com.x.Foo");
        Files.write(classes.resolve("Foo.class"), directoryFoo);
        Files.write(classes.resolve("package-info.class"), miniClass("com.x.package-info"));

        Path jarPath = tmp.resolve("deps.jar");
        byte[] jarFoo = miniClass("com.x.FooFromJar");
        byte[] jarBar = miniClass("com.x.Bar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jar.putNextEntry(new JarEntry("com/x/Foo.class"));
            jar.write(jarFoo);
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("com/x/Bar.class"));
            jar.write(jarBar);
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/versions/9/com/x/NewFoo.class"));
            jar.write(miniClass("com.x.NewFoo"));
            jar.closeEntry();
        }

        try (ClassFileSource source = new ClasspathClassFileSource(java.util.List.of(
                tmp.resolve("classes"), jarPath))) {
            assertEquals(java.util.Set.of("com.x.Foo", "com.x.Bar"), source.knownClasses());
            assertArrayEquals(directoryFoo, source.find("com.x.Foo").orElseThrow());
            assertArrayEquals(jarBar, source.find("com.x.Bar").orElseThrow());
            assertTrue(source.find("com.x.Missing").isEmpty());
        }
    }

    @Test
    void classpathSourcePersistsPackedOriginAndTypeCaches(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        Path jarPath = tmp.resolve("deps.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jar.putNextEntry(new JarEntry("com/x/Foo.class"));
            jar.write(miniClass("com.x.Foo"));
            jar.closeEntry();
        }
        String previous = System.getProperty(ClasspathClassFileSource.CACHE_DIR_PROPERTY);
        System.setProperty(ClasspathClassFileSource.CACHE_DIR_PROPERTY, cache.toString());
        try {
            try (ClasspathClassFileSource source = new ClasspathClassFileSource(List.of(jarPath));
                 AsmTypeSolver solver = new AsmTypeSolver(source)) {
                assertTrue(solver.tryToSolveType("com.x.Foo")
                        .getCorrespondingDeclaration().isClass());
                assertEquals(1, source.indexedClassCount());
                assertTrue(source.packedIndexBytes() > 0);
            }
            try (var files = Files.list(cache)) {
                assertEquals(2, files.count(), "origin and metadata caches should be written");
            }

            try (ClasspathClassFileSource source = new ClasspathClassFileSource(List.of(jarPath));
                 AsmTypeSolver solver = new AsmTypeSolver(source)) {
                Files.delete(jarPath);
                assertTrue(solver.tryToSolveType("com.x.Foo").isSolved(),
                        "warm metadata lookup must not reopen the jar");
            }
        } finally {
            if (previous == null) System.clearProperty(ClasspathClassFileSource.CACHE_DIR_PROPERTY);
            else System.setProperty(ClasspathClassFileSource.CACHE_DIR_PROPERTY, previous);
        }
    }

    @Test
    void classpathSourceInvalidatesCacheForJavaVersionAndJarChanges(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        Path jarPath = tmp.resolve("deps.jar");
        writeJar(jarPath, "com.x.Foo");
        String previous = System.getProperty(ClasspathClassFileSource.CACHE_DIR_PROPERTY);
        System.setProperty(ClasspathClassFileSource.CACHE_DIR_PROPERTY, cache.toString());
        try {
            String java8Fingerprint;
            try (ClasspathClassFileSource source = new ClasspathClassFileSource(List.of(jarPath), 8)) {
                java8Fingerprint = source.fingerprint();
                assertTrue(source.find("com.x.Foo").isPresent());
            }
            try (ClasspathClassFileSource source = new ClasspathClassFileSource(List.of(jarPath), 17)) {
                assertNotEquals(java8Fingerprint, source.fingerprint());
            }

            writeJar(jarPath, "com.x.Foo", "com.x.Bar");
            try (ClasspathClassFileSource source = new ClasspathClassFileSource(List.of(jarPath), 8)) {
                assertNotEquals(java8Fingerprint, source.fingerprint());
                assertTrue(source.find("com.x.Bar").isPresent());
            }
        } finally {
            if (previous == null) System.clearProperty(ClasspathClassFileSource.CACHE_DIR_PROPERTY);
            else System.setProperty(ClasspathClassFileSource.CACHE_DIR_PROPERTY, previous);
        }
    }

    private static void writeJar(Path jarPath, String... fqns) throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (String fqn : fqns) {
                jar.putNextEntry(new JarEntry(fqn.replace('.', '/') + ".class"));
                jar.write(miniClass(fqn));
                jar.closeEntry();
            }
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
