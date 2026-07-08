package com.anatomist.cli;

import com.anatomist.test.CliTestSupport;
import com.anatomist.test.CliTestSupport.RunResult;
import com.anatomist.store.IndexSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end smoke test against {@code fixtures/mini-spring-shop}.
 *
 * <p>Phase 1 of the JavaParser+SymbolSolver rewrite only wires
 * {@link com.anatomist.extract.TypeExtractor} and
 * {@link com.anatomist.extract.MethodExtractor}; the other extractors are
 * skeletons. This test verifies the baseline still holds: project types and
 * methods are emitted, CONTAINS edges link them, FTS5 indexing works.</p>
 */
class IndexCommandIT {

    @Test
    void recreateDeletesExistingDatabaseAndSidecarsBeforeIndex(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Files.writeString(project.resolve("README.md"),
                "# Stale doc\n\nold\n", StandardCharsets.UTF_8);

        Path db = tmp.resolve("index.db");
        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--output", db.toString(), "--format", "json");
        assertEquals(0, new CommandLine(new IndexDocsCommand()).execute(
                project.toString(), "--index", db.toString()));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(1, scalar(st, "SELECT count(*) FROM documents WHERE path='README.md'"));
        }

        byte[] staleWal = "stale-wal".getBytes(StandardCharsets.UTF_8);
        byte[] staleShm = "stale-shm".getBytes(StandardCharsets.UTF_8);
        Path wal = db.resolveSibling(db.getFileName() + "-wal");
        Path shm = db.resolveSibling(db.getFileName() + "-shm");
        Files.write(wal, staleWal);
        Files.write(shm, staleShm);

        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--output", db.toString(), "--recreate", "--format", "json");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(0, scalar(st, "SELECT count(*) FROM documents"),
                    "--recreate should rebuild the DB instead of preserving document rows");
            assertTrue(scalar(st, "SELECT count(*) FROM nodes WHERE qualified_name='p.A'") > 0,
                    "recreated index should contain freshly indexed Java nodes");
        }
        if (Files.exists(wal)) {
            assertFalse(java.util.Arrays.equals(staleWal, Files.readAllBytes(wal)),
                    "old WAL sidecar content should not survive --recreate");
        }
        if (Files.exists(shm)) {
            assertFalse(java.util.Arrays.equals(staleShm, Files.readAllBytes(shm)),
                    "old SHM sidecar content should not survive --recreate");
        }
    }

    @Test
    void indexesMiniSpringShop_typesMethodsContains(@TempDir Path tmp) throws Exception {
        Path fixture = CliTestSupport.miniSpringFixture();
        Path db = tmp.resolve("index.db");
        RunResult result = CliTestSupport.runIndex(fixture,
                "--project-source", CliTestSupport.miniSpringProjectSource(fixture),
                "--no-classpath",
                "--output", db.toString());
        assertEquals(0, result.exitCode(), "index should exit 0; stderr:\n" + result.stderr());
        String stdout = result.stdout();

        assertTrue(Files.exists(db), "db not produced: " + db);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            int classes  = scalar(st, "SELECT count(*) FROM nodes WHERE kind='CLASS'");
            int methods  = scalar(st, "SELECT count(*) FROM nodes WHERE kind='METHOD'");
            int contains = scalar(st, "SELECT count(*) FROM edges WHERE relation='CONTAINS'");
            int orderSvc = scalar(st,
                    "SELECT count(*) FROM nodes WHERE qualified_name='com.example.shop.service.OrderService'");
            int fts      = scalar(st,
                    "SELECT count(*) FROM node_names WHERE label MATCH 'OrderService'");
            int lambdas    = scalar(st, "SELECT count(*) FROM nodes WHERE kind='LAMBDA'");
            int methodRefs = scalar(st, "SELECT count(*) FROM nodes WHERE kind='METHOD_REF'");

            // Baseline post-gap-closure (monotonic floor — see CLAUDE.md Fixture section).
            assertTrue(classes  >= 4, "expected ≥4 CLASS nodes; got "  + classes);
            assertTrue(methods  >= 47, "expected ≥47 METHOD nodes; got " + methods);
            assertTrue(contains >= 75, "expected ≥75 CONTAINS edges; got " + contains);
            assertEquals(1, orderSvc,
                    "OrderService node missing or duplicated; got " + orderSvc);
            assertTrue(fts >= 1, "FTS5 should match OrderService; got " + fts);

            // REQ-001 / REQ-002 — at least one LAMBDA and one METHOD_REF from
            // the existing fixture (OrderService filter lambda, PriceCalculator
            // OrderItem::getPrice method reference).
            assertTrue(lambdas >= 1, "expected ≥1 LAMBDA node; got " + lambdas);
            assertTrue(methodRefs >= 1, "expected ≥1 METHOD_REF node; got " + methodRefs);

            int beans = scalar(st, "SELECT count(*) FROM nodes WHERE kind='BEAN'");
            int routes = scalar(st, "SELECT count(*) FROM nodes WHERE kind='ROUTE'");
            int injects = scalar(st, "SELECT count(*) FROM edges WHERE relation='INJECTS'");
            int handles = scalar(st, "SELECT count(*) FROM edges WHERE relation='HANDLES'");
            assertTrue(beans >= 6, "expected Spring stereotype BEAN nodes; got " + beans);
            assertEquals(2, routes, "expected 2 MVC ROUTE nodes");
            assertTrue(injects >= 5, "expected @Autowired INJECTS edges; got " + injects);
            assertEquals(2, handles, "expected 2 MVC HANDLES edges");

            // Phase 2 — semantic annotations & stdout line.
            // Built-in indexing must not infer architecture/business categories
            // from names or annotations.
            int conventionSemantic = scalar(st,
                    "SELECT count(*) FROM semantic_annotations WHERE source='CONVENTION'");
            assertEquals(0, conventionSemantic,
                    "expected no convention-based semantic annotations; got " + conventionSemantic);
            assertTrue(stdout.contains("Semantic annotations:"),
                    "stdout should contain 'Semantic annotations:' line; got:\n" + stdout);

            // REQ-005 / S11 — file_cache and project_meta are populated post-full-index.
            int fileCache = scalar(st, "SELECT count(*) FROM file_cache");
            assertTrue(fileCache > 0, "file_cache should be populated; got " + fileCache);
            int javaVerMeta = scalar(st, "SELECT count(*) FROM project_meta WHERE key='java_version'");
            assertEquals(1, javaVerMeta, "project_meta should contain java_version row");
            int cpHash = scalar(st, "SELECT count(*) FROM project_meta WHERE key='classpath_hash'");
            assertEquals(1, cpHash, "project_meta should contain classpath_hash row");
            assertEquals(fixture.toAbsolutePath().normalize().toString(),
                    scalarString(st, "SELECT value FROM project_meta WHERE key='source_root'"),
                    "project_meta should record source_root for source windows");
            assertNotNull(scalarString(st, "SELECT value FROM project_meta WHERE key='indexed_at'"),
                    "project_meta should record indexed_at");
            int fileDeps = scalar(st, "SELECT count(*) FROM file_dependencies");
            assertTrue(fileDeps >= 0, "file_dependencies table should be queryable");

            assertTrue(stdout.contains("File cache:"),
                    "stdout should contain 'File cache:' line; got:\n" + stdout);
        }
    }

    @Test
    void incrementalRecreatesIncompatibleSchema(@TempDir Path tmp) throws Exception {
        Path fixture = CliTestSupport.miniSpringFixture();
        String projectSource = CliTestSupport.miniSpringProjectSource(fixture);
        Path db = tmp.resolve("index.db");

        CliTestSupport.assertIndexOk(fixture,
                "--project-source", projectSource,
                "--no-classpath",
                "--output", db.toString());

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            st.execute("PRAGMA user_version = 1");
        }

        CliTestSupport.assertIndexOk(fixture,
                "--project-source", projectSource,
                "--no-classpath",
                "--incremental",
                "--output", db.toString());

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(IndexSchema.VERSION, scalar(st, "PRAGMA user_version"));
            assertTrue(scalar(st, "SELECT count(*) FROM file_cache") > 0,
                    "recreated index should repopulate file_cache");
            assertTrue(scalar(st, "SELECT count(*) FROM nodes WHERE kind='ROUTE'") > 0,
                    "recreated index should contain framework analyzer output");
        }
    }

    @Test
    void incrementalNoChangeSkipsMavenClasspathDetection(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Path db = tmp.resolve("index.db");

        CliTestSupport.assertIndexOk(project, "--no-classpath", "--output", db.toString());
        RunResult result = CliTestSupport.runIndex(project, "--incremental", "--output", db.toString());

        assertEquals(0, result.exitCode());
        String out = result.stdout();
        String err = result.stderr();
        assertTrue(out.contains("Changed files: 0"), out);
        assertTrue(out.contains("Written nodes: 0"), out);
        assertFalse(err.contains("Detecting classpath via Maven"),
                "no-op incremental should return before Maven classpath detection; stderr:\n" + err);
        assertFalse(err.contains("Parsing with Java"),
                "no-op incremental should not initialize JavaParser; stderr:\n" + err);
    }

    private static int scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static String scalarString(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    @Test
    void springXmlEmitsBeansWiresAndDefinedBy(@TempDir Path tmp) throws Exception {
        Path fixture = CliTestSupport.miniSpringFixture();
        String projectSource = CliTestSupport.miniSpringProjectSource(fixture);

        // With --spring-xml: BEAN nodes + DEFINED_BY + internal WIRES appear.
        Path withDb = tmp.resolve("with.db");
        CliTestSupport.assertIndexOk(fixture,
                "--project-source", projectSource,
                "--no-classpath",
                "--output", withDb.toString(),
                "--spring-xml");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + withDb);
             Statement st = c.createStatement()) {
            int beans = scalar(st, "SELECT count(*) FROM nodes WHERE kind='BEAN'");
            assertTrue(beans >= 10, "expected annotation + XML BEAN nodes; got " + beans);

            int definedByInternal = scalar(st,
                    "SELECT count(*) FROM edges WHERE relation='DEFINED_BY' AND is_external=0");
            assertTrue(definedByInternal >= 10,
                    "annotation + XML beans should map to indexed classes; got " + definedByInternal);

            // XML wires orderRepository/eventPublisher/priceCalculator; annotation
            // injection also narrows OrderRepository to InMemoryOrderRepository.
            int wiresInternal = scalar(st,
                    "SELECT count(*) FROM edges WHERE relation='WIRES' AND is_external=0 "
                            + "AND source_id='com.example.shop.service.OrderService'");
            assertTrue(wiresInternal >= 4,
                    "OrderService should wire XML collaborators plus DI narrowing; got " + wiresInternal);

            // The bean's source_file is the xml, and it's cached for incremental.
            int xmlCache = scalar(st,
                    "SELECT count(*) FROM file_cache WHERE source_file LIKE '%applicationContext.xml'");
            assertEquals(1, xmlCache, "xml should be in file_cache; got " + xmlCache);
        }

        // WIRES (CLASS->CLASS) must surface through the existing deps-of / used-by
        // query path — the whole point of modelling wiring as CLASS edges.
        try (com.anatomist.query.QueryService q = new com.anatomist.query.QueryService(withDb)) {
            java.util.List<com.anatomist.query.EdgeRow> deps =
                    q.depsOf("com.example.shop.service.OrderService");
            assertTrue(deps.stream().anyMatch(r -> "WIRES".equals(r.relation)
                            && "com.example.shop.service.PriceCalculator".equals(r.target)),
                    "deps-of OrderService should include WIRES → PriceCalculator");

            java.util.List<com.anatomist.query.EdgeRow> users =
                    q.usedBy("com.example.shop.service.PriceCalculator");
            assertTrue(users.stream().anyMatch(r -> "WIRES".equals(r.relation)
                            && "com.example.shop.service.OrderService".equals(r.source)),
                    "used-by PriceCalculator should include WIRES ← OrderService");
        }

        // Without the flag: annotation-driven Spring Boot concepts remain, XML
        // bean nodes stay off. Annotation DI may still emit WIRES.
        Path withoutDb = tmp.resolve("without.db");
        CliTestSupport.assertIndexOk(fixture,
                "--project-source", projectSource,
                "--no-classpath",
                "--output", withoutDb.toString());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + withoutDb);
             Statement st = c.createStatement()) {
            assertTrue(scalar(st, "SELECT count(*) FROM nodes WHERE kind='BEAN'") >= 6,
                    "annotation BEAN nodes should not require --spring-xml");
            assertEquals(0, scalar(st,
                    "SELECT count(*) FROM nodes WHERE kind='BEAN' AND source_file LIKE '%.xml'"),
                    "no --spring-xml ⇒ no XML BEAN nodes");
        }
    }
}
