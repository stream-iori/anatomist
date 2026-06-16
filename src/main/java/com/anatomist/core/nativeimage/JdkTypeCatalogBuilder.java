package com.anatomist.core.nativeimage;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Build-time tool that scans the running JDK's {@code jrt:/} module image
 *  and produces a {@link JdkTypeCatalog} for {@code META-INF/anatomist/jdkN-types.bin}. */
public class JdkTypeCatalogBuilder {

    public JdkTypeCatalog buildFromCurrentJdk() {
        int release = currentJdkRelease();
        // jrt-fs is a singleton — getFileSystem(jrt:/) returns it, and its
        // close() throws UnsupportedOperationException. Hold a plain reference.
        FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        try {
            return buildFrom(jrt, release);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    JdkTypeCatalog buildFrom(FileSystem jrt, int release) throws IOException {
        JdkTypeCatalog cat = new JdkTypeCatalog(release);
        Path modulesRoot = jrt.getPath("modules");
        // /modules/<moduleName>/<package>/<...>/<Class>.class
        try (Stream<Path> moduleDirs = Files.list(modulesRoot)) {
            for (Path moduleDir : (Iterable<Path>) moduleDirs::iterator) {
                String moduleName = moduleDir.getFileName().toString();
                if (!includeModule(moduleName)) continue;
                try (Stream<Path> walk = Files.walk(moduleDir)) {
                    for (Path p : (Iterable<Path>) walk::iterator) {
                        if (!p.toString().endsWith(".class")) continue;
                        if (p.getFileName().toString().equals("module-info.class")) continue;
                        if (p.getFileName().toString().equals("package-info.class")) continue;
                        try (InputStream in = Files.newInputStream(p)) {
                            JdkType t = parseClass(in);
                            if (t != null) cat.add(t);
                        } catch (IOException e) {
                            // Skip unreadable entries silently — better to ship a partial catalog
                            // than fail the whole build for one rogue class.
                        }
                    }
                }
            }
        }
        return cat;
    }

    /** Heuristic: only include modules an application would normally see on
     *  the unnamed classpath. Skip JDK internals / tooling that bloat the
     *  catalog without anatomist using them. */
    private static boolean includeModule(String module) {
        if (module.startsWith("jdk.internal")) return false;
        if (module.startsWith("jdk.compiler")) return false;
        if (module.startsWith("jdk.javadoc")) return false;
        if (module.startsWith("jdk.jdi")) return false;
        if (module.startsWith("jdk.jstatd")) return false;
        if (module.startsWith("jdk.jconsole")) return false;
        if (module.startsWith("jdk.jdeps")) return false;
        if (module.startsWith("jdk.jlink")) return false;
        if (module.startsWith("jdk.management")) return false;
        if (module.startsWith("jdk.localedata")) return false;
        return true;
    }

    JdkType parseClass(InputStream in) throws IOException {
        ClassReader cr = new ClassReader(in);
        TypeVisitor v = new TypeVisitor();
        cr.accept(v, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return v.toType();
    }

    private static int currentJdkRelease() {
        // Runtime.version().feature() returns 21 on JDK 21+
        return Runtime.version().feature();
    }

    private static final class TypeVisitor extends ClassVisitor {
        String fqn;
        String superFqn;
        String classSignature;
        List<String> interfaces = new ArrayList<>();
        int classFlags;
        List<JdkType.FieldEntry> fields = new ArrayList<>();
        List<JdkType.MethodEntry> methods = new ArrayList<>();
        boolean skipped;

        TypeVisitor() { super(Opcodes.ASM9); }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            // Skip synthetic / inner-class noise that anatomist doesn't need.
            if ((access & Opcodes.ACC_SYNTHETIC) != 0) { skipped = true; return; }
            this.fqn = name.replace('/', '.');
            this.classSignature = signature;
            // Interface "extends Object" in bytecode but anatomist treats interfaces as super=null.
            boolean isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
            this.superFqn = isInterface || superName == null
                    ? null
                    : superName.replace('/', '.');
            if (interfaces != null) {
                for (String i : interfaces) this.interfaces.add(i.replace('/', '.'));
            }
            this.classFlags = translateFlags(access, /*isMember*/ false);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            if (skipped) return null;
            if ((access & Opcodes.ACC_SYNTHETIC) != 0) return null;
            fields.add(new JdkType.FieldEntry(name, descriptor, translateFlags(access, true), signature));
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            if (skipped) return null;
            if ((access & Opcodes.ACC_SYNTHETIC) != 0) return null;
            if ((access & Opcodes.ACC_BRIDGE) != 0) return null;
            methods.add(new JdkType.MethodEntry(name, descriptor, translateFlags(access, true), signature));
            return null;
        }

        JdkType toType() {
            if (skipped || fqn == null) return null;
            return new JdkType(fqn, superFqn, interfaces, classFlags, classSignature, fields, methods);
        }

        private static int translateFlags(int asmAccess, boolean isMember) {
            int f = 0;
            if ((asmAccess & Opcodes.ACC_PUBLIC) != 0)    f |= JdkType.FLAG_PUBLIC;
            if ((asmAccess & Opcodes.ACC_STATIC) != 0)    f |= JdkType.FLAG_STATIC;
            if ((asmAccess & Opcodes.ACC_FINAL) != 0)     f |= JdkType.FLAG_FINAL;
            if ((asmAccess & Opcodes.ACC_INTERFACE) != 0) f |= JdkType.FLAG_INTERFACE;
            if ((asmAccess & Opcodes.ACC_ABSTRACT) != 0)  f |= JdkType.FLAG_ABSTRACT;
            if ((asmAccess & Opcodes.ACC_ENUM) != 0)      f |= JdkType.FLAG_ENUM;
            if ((asmAccess & Opcodes.ACC_ANNOTATION) != 0) f |= JdkType.FLAG_ANNOTATION;
            if ((asmAccess & Opcodes.ACC_RECORD) != 0)    f |= JdkType.FLAG_RECORD;
            // ACC_INTERFACE and ACC_ENUM are mutually exclusive with class — set CLASS otherwise.
            if (!isMember
                    && (asmAccess & Opcodes.ACC_INTERFACE) == 0
                    && (asmAccess & Opcodes.ACC_ENUM) == 0
                    && (asmAccess & Opcodes.ACC_ANNOTATION) == 0) {
                f |= JdkType.FLAG_CLASS;
            }
            return f;
        }
    }
}
