package com.anatomist.cli;

import com.anatomist.query.NodeRow;
import com.anatomist.query.QueryService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * L3 smoke test against an Apache Commons Lang 3.12.0 source tree (JDK 8).
 *
 * <p>Why this fixture: Commons Lang is a dense, dependency-free JDK 8 library
 * with hundreds of types, exercising the indexer at scale without dragging in
 * a framework runtime. testing-strategy.md §二 Fixture C lists it as a
 * candidate for scale/regression smoke.</p>
 *
 * <p><b>Skip behaviour:</b> each test calls {@link #requireSubmodule()} so
 * Surefire reports a per-test "Skipped" entry (not "Tests run: 0") when
 * {@code fixtures/external/commons-lang} is absent. See
 * {@code fixtures/external/README.md} for setup.</p>
 */
@Tag("external")
class CommonsLangSmokeIT {

    private static Path repoRoot;
    private static Path commonsLang;
    private static Path commonsLangSrc;
    private static Path dbPath;

    @BeforeAll
    static void buildIndex(@TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER) Path tmp)
            throws Exception {
        repoRoot = Path.of(System.getProperty("user.dir"));
        commonsLang = repoRoot.resolve("fixtures/external/commons-lang");
        commonsLangSrc = commonsLang.resolve("src/main/java");

        // Don't abort in @BeforeAll — that produces a silent "Tests run: 0".
        // Tests gate themselves via requireSubmodule() so they appear as
        // Skipped one-by-one in the Surefire report.
        if (!Files.isDirectory(commonsLangSrc)) {
            System.err.println("[CommonsLangSmokeIT] submodule missing — tests will skip. "
                    + "See fixtures/external/README.md to enable.");
            return;
        }

        dbPath = tmp.resolve("commons-lang.db");
        IndexCommand cmd = new IndexCommand();
        new CommandLine(cmd).parseArgs(
                commonsLang.toString(),
                "--project-source", commonsLangSrc.toString(),
                "--no-classpath",
                "--java-version", "8",
                "--output", dbPath.toString());

        PrintStream old = System.out;
        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(cap, true, StandardCharsets.UTF_8));
            int rc = cmd.call();
            assertEquals(0, rc, "indexing commons-lang should not crash. log:\n"
                    + cap.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(old);
        }
        assertTrue(Files.exists(dbPath));
    }

    /** Per-test gate: assumeTrue here so Surefire shows Skipped rather than Aborted. */
    private static void requireSubmodule() {
        assumeTrue(dbPath != null && Files.isRegularFile(dbPath),
                "commons-lang submodule not checked out — see fixtures/external/README.md");
    }

    @Test
    void index_producesReasonableNodeAndEdgeCounts() throws Exception {
        requireSubmodule();
        int types  = scalar("SELECT count(*) FROM nodes "
                + " WHERE kind IN ('CLASS','INTERFACE','ENUM','ANNOTATION')");
        int methods = scalar("SELECT count(*) FROM nodes WHERE kind='METHOD'");
        int edges   = scalar("SELECT count(*) FROM edges");
        // Commons Lang 3.12.0 has hundreds of types and thousands of methods —
        // generous floors so the test stays stable across point releases.
        assertTrue(types   >= 100,  "expected ≥100 types; got "  + types);
        assertTrue(methods >= 1000, "expected ≥1000 methods; got " + methods);
        assertTrue(edges   >= 1000, "expected ≥1000 edges; got "  + edges);
    }

    @Test
    void wellKnownClass_StringUtils_isPresent() throws Exception {
        requireSubmodule();
        int count = scalar("SELECT count(*) FROM nodes "
                + " WHERE qualified_name='org.apache.commons.lang3.StringUtils'");
        assertEquals(1, count, "StringUtils should be present exactly once");

        // ObjectUtils and ArrayUtils are equally canonical.
        int companions = scalar("SELECT count(*) FROM nodes "
                + " WHERE qualified_name IN ("
                + "   'org.apache.commons.lang3.ObjectUtils',"
                + "   'org.apache.commons.lang3.ArrayUtils')");
        assertEquals(2, companions, "ObjectUtils + ArrayUtils both expected");
    }

    @Test
    void queryLayer_searchReturnsResults() {
        requireSubmodule();
        try (QueryService q = new QueryService(dbPath)) {
            List<NodeRow> rows = Stream.of("StringUtils", "ObjectUtils", "ArrayUtils", "Validate")
                    .map(t -> q.search(t, null, 5))
                    .filter(l -> !l.isEmpty())
                    .findFirst()
                    .orElse(List.of());
            assertFalse(rows.isEmpty(), "search on commons-lang utility name yielded nothing");
        }
    }

    private static int scalar(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
