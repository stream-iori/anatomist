package com.anatomist.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.File;
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
        assertEquals(0, cmd.call(), "index should exit 0");

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
        }
    }

    private static int scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
