package com.anatomist.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Force the symbol solver to resolve a symbol from a jar (vs source) by
 *  building a tiny jar with one class and a Java source file that calls
 *  one of its methods. Diff the indexed output across javassist and asm
 *  solvers — must match exactly. */
class AsmVsJavassistJarDiffIT {

    @Test
    void simpleJarBackedCall_indexesIdentically(@TempDir Path tmp) throws Exception {
        // 1) Build a one-class jar: com.lib.Greeter with public String hello(String).
        Path libDir = tmp.resolve("lib");
        Files.createDirectories(libDir);
        Path jar = libDir.resolve("greeter.jar");
        try (JarOutputStream jout = new JarOutputStream(Files.newOutputStream(jar))) {
            jout.putNextEntry(new JarEntry("com/lib/Greeter.class"));
            jout.write(greeterClass());
            jout.closeEntry();
        }

        // 2) Build a project that calls Greeter#hello.
        Path project = tmp.resolve("project");
        Path src = project.resolve("src/main/java/com/app");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"),
                "package com.app;\n" +
                "import com.lib.Greeter;\n" +
                "public class App {\n" +
                "    public String run() {\n" +
                "        Greeter g = new Greeter();\n" +
                "        return g.hello(\"world\");\n" +
                "    }\n" +
                "}\n",
                StandardCharsets.UTF_8);

        // 3) Index twice — javassist and asm.
        Path dbJ = tmp.resolve("j.db");
        Path dbA = tmp.resolve("a.db");
        runIndex(project, src, jar, dbJ, false);
        runIndex(project, src, jar, dbA, true);

        // 4) Diff core tables. Of particular interest: the external CALLS edge
        //    for Greeter#hello must show is_external=1 with the right FQN in
        //    BOTH dbs — this is the path that actually hits the jar solver.
        assertSameRows(dbJ, dbA,
                "SELECT source_id, target_id, external_target_fqn, relation, call_kind, is_external, source_location "
              + " FROM edges WHERE relation = 'CALLS' "
              + " ORDER BY source_id, COALESCE(target_id,''), COALESCE(external_target_fqn,'')");

        // The hello call must resolve to com.lib.Greeter#hello with parameter erased to java.lang.String.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbA.toAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT external_target_fqn FROM edges WHERE is_external=1 AND relation='CALLS'")) {
            boolean foundHello = false;
            while (rs.next()) {
                String fqn = rs.getString(1);
                if (fqn != null && fqn.startsWith("com.lib.Greeter#hello(")) foundHello = true;
            }
            assertTrue(foundHello,
                    "AsmTypeSolver path must resolve com.lib.Greeter#hello as an external CALL");
        }
    }

    /** Synthesise the Greeter class via ASM:
     *  public class com.lib.Greeter {
     *      public String hello(String name) { return "Hello " + name; }
     *  } */
    private static byte[] greeterClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/lib/Greeter",
                null, "java/lang/Object", null);
        // default ctor
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        // hello — returns its argument directly (body content irrelevant for symbol resolution)
        MethodVisitor hello = cw.visitMethod(Opcodes.ACC_PUBLIC, "hello",
                "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        hello.visitCode();
        hello.visitVarInsn(Opcodes.ALOAD, 1);
        hello.visitInsn(Opcodes.ARETURN);
        hello.visitMaxs(0, 0);
        hello.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void runIndex(Path project, Path source, Path jar,
                                 Path output, boolean useAsm) throws Exception {
        IndexCommand cmd = new IndexCommand();
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add(project.toString());
        args.add("--project-source"); args.add(source.toString());
        args.add("--classpath");      args.add(jar.toString());
        args.add("--output");         args.add(output.toString());
        if (!useAsm) args.add("--legacy-solver");
        new CommandLine(cmd).parseArgs(args.toArray(new String[0]));

        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        PrintStream old = System.out;
        try {
            System.setOut(new PrintStream(cap, true, StandardCharsets.UTF_8));
            int rc = cmd.call();
            assertEquals(0, rc, "index failed (asm=" + useAsm + "):\n"
                    + cap.toString(StandardCharsets.UTF_8));
        } finally { System.setOut(old); }
    }

    private static void assertSameRows(Path dbA, Path dbB, String sql) throws Exception {
        Set<String> a = dump(dbA, sql);
        Set<String> b = dump(dbB, sql);
        if (!a.equals(b)) {
            Set<String> onlyA = new LinkedHashSet<>(a); onlyA.removeAll(b);
            Set<String> onlyB = new LinkedHashSet<>(b); onlyB.removeAll(a);
            fail("Row diff between javassist and asm.\n"
                    + "  Only javassist (" + onlyA.size() + "): " + onlyA + "\n"
                    + "  Only asm       (" + onlyB.size() + "): " + onlyB + "\n"
                    + "  query: " + sql);
        }
    }

    private static Set<String> dump(Path db, String sql) throws Exception {
        Set<String> rows = new LinkedHashSet<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= cols; i++) {
                    if (i > 1) sb.append('|');
                    Object v = rs.getObject(i);
                    sb.append(v == null ? "<null>" : v.toString());
                }
                rows.add(sb.toString());
            }
        }
        return rows;
    }
}
