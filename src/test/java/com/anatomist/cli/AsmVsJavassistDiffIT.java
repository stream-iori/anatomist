package com.anatomist.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Cross-validate AsmTypeSolver against the stock javassist-backed JarTypeSolver:
 *  index the same fixture twice (once with each), then diff the resulting
 *  SQLite databases. Same input → same node/edge/annotation tables → identical
 *  downstream behaviour. Any diff is a bug in AsmTypeSolver. */
class AsmVsJavassistDiffIT {

    @Test
    void miniSpringShop_indexedWithBothSolvers_producesSameRows(@TempDir Path tmp) throws Exception {
        Path repo = Path.of(System.getProperty("user.dir"));
        Path fixture = repo.resolve("fixtures/mini-spring-shop");
        assertTrue(Files.isDirectory(fixture));

        Path dbJavassist = tmp.resolve("javassist.db");
        Path dbAsm = tmp.resolve("asm.db");

        runIndex(fixture, dbJavassist, /*useAsm=*/false);
        runIndex(fixture, dbAsm, /*useAsm=*/true);

        // Compare three core tables row-by-row.
        assertSameRows(dbJavassist, dbAsm,
                "SELECT id, label, kind, qualified_name, package, source_file, source_location, module, scope FROM nodes ORDER BY id");
        assertSameRows(dbJavassist, dbAsm,
                "SELECT source_id, target_id, external_target_fqn, relation, call_kind, is_external, source_file, source_location FROM edges ORDER BY source_id, COALESCE(target_id, ''), COALESCE(external_target_fqn, ''), relation, call_kind, COALESCE(source_location, '')");
        assertSameRows(dbJavassist, dbAsm,
                "SELECT node_id, annotation_fqn, attributes FROM annotations ORDER BY node_id, annotation_fqn");
    }

    private static void runIndex(Path fixture, Path output, boolean useAsm) throws Exception {
        String projectSource = String.join(File.pathSeparator,
                fixture.resolve("api/src/main/java").toString(),
                fixture.resolve("domain/src/main/java").toString(),
                fixture.resolve("service/src/main/java").toString());

        IndexCommand cmd = new IndexCommand();
        List<String> args = new ArrayList<>();
        args.add(fixture.toString());
        args.add("--project-source"); args.add(projectSource);
        args.add("--no-classpath");                          // mini-spring-shop has no jar deps
        args.add("--output"); args.add(output.toString());
        if (!useAsm) args.add("--legacy-solver");
        new CommandLine(cmd).parseArgs(args.toArray(new String[0]));

        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        PrintStream old = System.out;
        try {
            System.setOut(new PrintStream(cap, true, StandardCharsets.UTF_8));
            int rc = cmd.call();
            assertEquals(0, rc,
                    "index failed (useAsm=" + useAsm + "):\n" + cap.toString(StandardCharsets.UTF_8));
        } finally { System.setOut(old); }
        assertTrue(Files.exists(output), "index db missing: " + output);
    }

    private static void assertSameRows(Path dbA, Path dbB, String sql) throws Exception {
        Set<String> a = dump(dbA, sql);
        Set<String> b = dump(dbB, sql);
        if (!a.equals(b)) {
            Set<String> onlyA = new LinkedHashSet<>(a); onlyA.removeAll(b);
            Set<String> onlyB = new LinkedHashSet<>(b); onlyB.removeAll(a);
            fail("Row diff between javassist and asm.\n  Only in javassist (" + onlyA.size() + "): "
                    + onlyA.stream().limit(10).toList() + "\n  Only in asm (" + onlyB.size() + "): "
                    + onlyB.stream().limit(10).toList() + "\n  query: " + sql);
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
                // JavaParser stamps a fresh UUID into the qualified_name of
                // unresolved anonymous classes on every parse run (regardless
                // of solver). Strip it before comparing.
                rows.add(sb.toString().replaceAll(
                        "Anonymous-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                        "Anonymous-<uuid>"));
            }
        }
        return rows;
    }
}
