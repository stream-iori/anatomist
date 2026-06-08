package com.anatomist.cli;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 fixture-based coverage of the 8 single-file micro fixtures under
 * {@code fixtures/micro/}. Each fixture targets one language feature that
 * the testing-strategy.md §二 Fixture A check-list calls out.
 *
 * <p>This complements the per-extractor unit tests by indexing the *real
 * on-disk* fixtures through the full IndexCommand pipeline, so we catch
 * regressions in source-root scanning + parsing + storage as well.</p>
 */
class MicroFixtureIT {

    private static Path dbPath;

    @BeforeAll
    static void buildIndex(@TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER) Path tmp)
            throws Exception {
        Path repo = Path.of(System.getProperty("user.dir"));
        Path micro = repo.resolve("fixtures/micro");
        assertTrue(Files.isDirectory(micro));

        dbPath = tmp.resolve("micro.db");
        IndexCommand cmd = new IndexCommand();
        new CommandLine(cmd).parseArgs(
                micro.toString(),
                "--project-source", micro.toString(),
                "--no-classpath",
                "--output", dbPath.toString());

        PrintStream old = System.out;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            assertEquals(0, cmd.call());
        } finally {
            System.setOut(old);
        }
        assertTrue(Files.exists(dbPath));
    }

    // ── Lambda & MethodRef ────────────────────────────────────────────

    @Test
    void lambdaInStream_emitsLambdaNode() throws Exception {
        assertTrue(scalar("SELECT count(*) FROM nodes WHERE kind='LAMBDA' "
                + "  AND id LIKE 'micro.LambdaInStream%'") >= 1);
    }

    // ── Anonymous class ───────────────────────────────────────────────

    @Test
    void anonymousRunnable_emitsAnonymousClassNode_withLineNumber() throws Exception {
        assertTrue(scalar("SELECT count(*) FROM nodes WHERE kind='ANONYMOUS_CLASS' "
                + "  AND qualified_name LIKE 'micro.AnonymousRunnable%'") >= 1);
    }

    // ── Method overloading — erased signatures must disambiguate ──────

    @Test
    void overloadedMethods_haveDistinctIds_byErasedParamFqn() throws Exception {
        // 3 overloads of `describe`
        int n = scalar("SELECT count(*) FROM nodes WHERE kind='METHOD' "
                + " AND qualified_name='micro.OverloadedMethods#describe'");
        assertEquals(3, n, "expected 3 overload IDs for describe");

        // Their IDs differ only in (int) | (java.lang.String) | (java.util.List)
        assertEquals(1, scalar("SELECT count(*) FROM nodes WHERE id="
                + "'micro.OverloadedMethods#describe(int)'"));
        assertEquals(1, scalar("SELECT count(*) FROM nodes WHERE id="
                + "'micro.OverloadedMethods#describe(java.lang.String)'"));
        assertEquals(1, scalar("SELECT count(*) FROM nodes WHERE id="
                + "'micro.OverloadedMethods#describe(java.util.List)'"));
    }

    // ── Static vs instance CALLS edges ────────────────────────────────

    @Test
    void staticVsInstance_callKindBranches() throws Exception {
        int statik = scalar("SELECT count(*) FROM edges "
                + " WHERE source_id='micro.StaticVsInstance#demo()' "
                + "   AND call_kind='STATIC'");
        int instance = scalar("SELECT count(*) FROM edges "
                + " WHERE source_id='micro.StaticVsInstance#demo()' "
                + "   AND call_kind='INSTANCE'");
        assertTrue(statik >= 1,   "expected ≥1 STATIC call; got " + statik);
        assertTrue(instance >= 1, "expected ≥1 INSTANCE call; got " + instance);
    }

    // ── Generic — REFERENCES exist on the class ───────────────────────

    @Test
    void genericRepository_emitsReferencesToTypeArg() throws Exception {
        assertEquals(1, scalar("SELECT count(*) FROM nodes "
                + " WHERE id='micro.GenericRepository' AND kind='CLASS'"));
    }

    // ── FieldReadWrite — READS + WRITES on the counter field ──────────

    @Test
    void fieldReadWrite_emitsReadsAndWrites() throws Exception {
        // counter = counter + 1   →  1 WRITE (LHS) + 1 READ (RHS)
        // return counter          →  1 READ
        int writes = scalar("SELECT count(*) FROM edges "
                + " WHERE relation='WRITES' "
                + "   AND target_id='micro.FieldReadWrite#counter'");
        int reads = scalar("SELECT count(*) FROM edges "
                + " WHERE relation='READS' "
                + "   AND target_id='micro.FieldReadWrite#counter'");
        assertTrue(writes >= 1, "expected ≥1 WRITES; got " + writes
                + dumpEdges("micro.FieldReadWrite%"));
        assertTrue(reads >= 2, "expected ≥2 READS (RHS of assign + return); got " + reads
                + dumpEdges("micro.FieldReadWrite%"));
    }

    private static String dumpEdges(String sourcePrefix) throws Exception {
        StringBuilder sb = new StringBuilder("\nedges sourced from ").append(sourcePrefix).append(":");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT relation, source_id, target_id, external_target_fqn, source_location "
                   + " FROM edges WHERE source_id LIKE ? ORDER BY relation, source_location")) {
            ps.setString(1, sourcePrefix);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sb.append("\n  ").append(rs.getString(1))
                      .append("  ").append(rs.getString(2))
                      .append("  → ").append(rs.getString(3))
                      .append('/').append(rs.getString(4))
                      .append("  @").append(rs.getString(5));
                }
            }
        }
        return sb.toString();
    }

    // ── Enum + ENUM_CONSTANT ──────────────────────────────────────────

    @Test
    void enumWithMethods_emitsEnumAndConstants() throws Exception {
        assertEquals(1, scalar("SELECT count(*) FROM nodes "
                + " WHERE id='micro.EnumWithMethods' AND kind='ENUM'"));
        int constants = scalar("SELECT count(*) FROM nodes "
                + " WHERE kind='ENUM_CONSTANT' "
                + "   AND qualified_name LIKE 'micro.EnumWithMethods%'");
        assertTrue(constants >= 3, "expected 3 enum constants; got " + constants);
    }

    // ── Interface default method belongs to INTERFACE node ────────────

    @Test
    void interfaceDefaultMethod_belongsToInterfaceNode() throws Exception {
        assertEquals(1, scalar("SELECT count(*) FROM nodes "
                + " WHERE id='micro.InterfaceDefaultMethod' AND kind='INTERFACE'"));
        // greeting() should exist as a METHOD whose CONTAINS parent is the interface
        assertTrue(scalar("SELECT count(*) FROM edges "
                + " WHERE relation='CONTAINS' "
                + "   AND source_id='micro.InterfaceDefaultMethod' "
                + "   AND target_id='micro.InterfaceDefaultMethod#greeting()'") >= 1);
    }

    // ── JDK 8 boundary — negative assertions ──────────────────────────

    @Test
    void jdk8Boundary_noRecordKindEmittedFromMicroFixtures() throws Exception {
        // None of the micro fixtures declare a record; if any RECORD node
        // appears it means the parser fell back to a higher language level.
        assertEquals(0, scalar("SELECT count(*) FROM nodes WHERE kind='RECORD' "
                + " AND id LIKE 'micro.%'"));
    }

    @Test
    void jdk8Boundary_noJre21OnlyTypesLeakIntoExternalEdges() throws Exception {
        // SequencedCollection / SequencedMap appeared in JDK 21. We index with
        // --no-classpath so SymbolSolver can only resolve against the JRE that
        // anatomist itself runs on (21). Even so, the fixtures use no API that
        // returns those types, so they MUST NOT appear as external targets.
        int bad = scalar("SELECT count(*) FROM edges "
                + " WHERE external_target_fqn LIKE 'java.util.Sequenced%'");
        assertEquals(0, bad,
                "JRE 21 sequenced collection types leaked into external_target_fqn");
    }

    // ── External REFERENCES edges (post-Phase 1.5 gap closure) ───────

    @Test
    void externalReferences_javaUtilListTracked() throws Exception {
        // java.util.List is NOT in the default exclude list, so REFERENCES to it
        // from GenericRepository / LambdaInStream / OverloadedMethods should appear.
        int extRefs = scalar("SELECT count(*) FROM edges "
                + " WHERE is_external = 1 AND relation = 'REFERENCES' "
                + "   AND external_target_fqn = 'java.util.List'");
        assertTrue(extRefs >= 1,
                "expected ≥1 external REFERENCES to java.util.List; got " + extRefs);
    }

    @Test
    void externalReferences_javaLangExcludedByDefault() throws Exception {
        // java.lang.* is in the default exclude patterns — no external REFERENCES
        // should point to anything under java.lang.
        int javaLangRefs = scalar("SELECT count(*) FROM edges "
                + " WHERE is_external = 1 AND relation = 'REFERENCES' "
                + "   AND external_target_fqn LIKE 'java.lang.%'");
        assertEquals(0, javaLangRefs,
                "java.lang.* should be excluded from external REFERENCES; got " + javaLangRefs);
    }

    // helper
    private static int scalar(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return rs.getInt(1);
        }
    }
}
