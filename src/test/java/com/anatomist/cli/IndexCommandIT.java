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
    void indexesMiniSpringShop_typesMethodsContains(@TempDir Path tmp) throws Exception {
        Path repoRoot = Path.of(System.getProperty("user.dir"));
        Path fixture = repoRoot.resolve("fixtures/mini-spring-shop");
        assertTrue(Files.isDirectory(fixture), "fixture missing: " + fixture);

        Path db = tmp.resolve("index.db");

        String projectSource = String.join(File.pathSeparator,
                fixture.resolve("api/src/main/java").toString(),
                fixture.resolve("domain/src/main/java").toString(),
                fixture.resolve("service/src/main/java").toString());

        IndexCommand cmd = new IndexCommand();
        new CommandLine(cmd).parseArgs(
                fixture.toString(),
                "--project-source", projectSource,
                "--no-classpath",
                "--output", db.toString()
        );

        ByteArrayOutputStream stdoutCapture = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        int rc;
        try {
            System.setOut(new PrintStream(stdoutCapture, true, StandardCharsets.UTF_8));
            rc = cmd.call();
        } finally {
            System.setOut(originalOut);
        }
        String stdout = stdoutCapture.toString(StandardCharsets.UTF_8);
        System.out.print(stdout);
        assertEquals(0, rc, "index should exit 0");

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
            // Note: this IT runs with --no-classpath, so SymbolSolver cannot
            // resolve Spring annotation FQNs (only java.lang.Override survives).
            // Therefore only naming rules fire on this fixture (≥6 hits).
            // Annotation-rule coverage is proven by SemanticPostProcessorTest.
            int semantic = scalar(st, "SELECT count(*) FROM semantic_annotations");
            assertTrue(semantic >= 6,
                    "expected ≥6 semantic_annotations rows on fixture (naming rules); got " + semantic);
            assertTrue(stdout.contains("Semantic annotations:"),
                    "stdout should contain 'Semantic annotations:' line; got:\n" + stdout);

            // REQ-005 / S11 — file_cache and project_meta are populated post-full-index.
            int fileCache = scalar(st, "SELECT count(*) FROM file_cache");
            assertTrue(fileCache > 0, "file_cache should be populated; got " + fileCache);
            int javaVerMeta = scalar(st, "SELECT count(*) FROM project_meta WHERE key='java_version'");
            assertEquals(1, javaVerMeta, "project_meta should contain java_version row");
            int cpHash = scalar(st, "SELECT count(*) FROM project_meta WHERE key='classpath_hash'");
            assertEquals(1, cpHash, "project_meta should contain classpath_hash row");
            int fileDeps = scalar(st, "SELECT count(*) FROM file_dependencies");
            assertTrue(fileDeps >= 0, "file_dependencies table should be queryable");

            assertTrue(stdout.contains("File cache:"),
                    "stdout should contain 'File cache:' line; got:\n" + stdout);
        }
    }

    private static int scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static int indexWithArgs(Path fixture, Path db, String projectSource, boolean springXml) {
        IndexCommand cmd = new IndexCommand();
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
                fixture.toString(),
                "--project-source", projectSource,
                "--no-classpath",
                "--output", db.toString()));
        if (springXml) args.add("--spring-xml");
        new CommandLine(cmd).parseArgs(args.toArray(new String[0]));
        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        PrintStream orig = System.out;
        try {
            System.setOut(new PrintStream(cap, true, StandardCharsets.UTF_8));
            return cmd.call();
        } finally {
            System.setOut(orig);
        }
    }

    @Test
    void springXmlEmitsBeansWiresAndDefinedBy(@TempDir Path tmp) throws Exception {
        Path repoRoot = Path.of(System.getProperty("user.dir"));
        Path fixture = repoRoot.resolve("fixtures/mini-spring-shop");
        assertTrue(Files.isDirectory(fixture));
        String projectSource = String.join(File.pathSeparator,
                fixture.resolve("api/src/main/java").toString(),
                fixture.resolve("domain/src/main/java").toString(),
                fixture.resolve("service/src/main/java").toString());

        // With --spring-xml: BEAN nodes + DEFINED_BY + internal WIRES appear.
        Path withDb = tmp.resolve("with.db");
        assertEquals(0, indexWithArgs(fixture, withDb, projectSource, true));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + withDb);
             Statement st = c.createStatement()) {
            int beans = scalar(st, "SELECT count(*) FROM nodes WHERE kind='BEAN'");
            assertTrue(beans >= 10, "expected annotation + XML BEAN nodes; got " + beans);

            int definedByInternal = scalar(st,
                    "SELECT count(*) FROM edges WHERE relation='DEFINED_BY' AND is_external=0");
            assertTrue(definedByInternal >= 10,
                    "annotation + XML beans should map to indexed classes; got " + definedByInternal);

            // OrderService bean wires orderRepository/eventPublisher/priceCalculator,
            // all CLASS->CLASS internal edges.
            int wiresInternal = scalar(st,
                    "SELECT count(*) FROM edges WHERE relation='WIRES' AND is_external=0 "
                            + "AND source_id='com.example.shop.service.OrderService'");
            assertEquals(3, wiresInternal,
                    "OrderService should wire 3 collaborators; got " + wiresInternal);

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

        // Without the flag: annotation-driven Spring Boot concepts remain, XML-only
        // WIRES stay off.
        Path withoutDb = tmp.resolve("without.db");
        assertEquals(0, indexWithArgs(fixture, withoutDb, projectSource, false));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + withoutDb);
             Statement st = c.createStatement()) {
            assertTrue(scalar(st, "SELECT count(*) FROM nodes WHERE kind='BEAN'") >= 6,
                    "annotation BEAN nodes should not require --spring-xml");
            assertEquals(0, scalar(st, "SELECT count(*) FROM edges WHERE relation='WIRES'"),
                    "no --spring-xml ⇒ no XML WIRES edges");
        }
    }
}
