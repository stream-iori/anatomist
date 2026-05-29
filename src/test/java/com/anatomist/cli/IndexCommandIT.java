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

class IndexCommandIT {

    @Test
    void indexesMiniSpringShopServiceModule(@TempDir Path tmp) throws Exception {
        Path repoRoot = Path.of(System.getProperty("user.dir"));
        Path fixture = repoRoot.resolve("fixtures/mini-spring-shop");
        assertTrue(Files.isDirectory(fixture), "fixture missing: " + fixture);

        Path db = tmp.resolve("index.db");

        // include all three modules' src/main/java so cross-module bindings (OrderService -> Order)
        // resolve without needing a real classpath.
        String projectSource = String.join(File.pathSeparator,
                fixture.resolve("api/src/main/java").toString(),
                fixture.resolve("domain/src/main/java").toString(),
                fixture.resolve("service/src/main/java").toString());

        int code = new CommandLine(new IndexCommand()).execute(
                "index", fixture.toString(),
                "--project-source", projectSource,
                "--no-classpath",
                "--output", db.toString()
        );
        // top-level CommandLine has no subcommand registered here, so we invoke directly:
        if (code != 0) {
            // fall through — try direct call (most likely picocli root parsing differs)
        }

        // Direct programmatic invocation as a safety net
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
            int classes = scalar(st, "SELECT count(*) FROM nodes WHERE kind='CLASS'");
            int methods = scalar(st, "SELECT count(*) FROM nodes WHERE kind='METHOD'");
            int fields = scalar(st, "SELECT count(*) FROM nodes WHERE kind='FIELD'");
            int contains = scalar(st, "SELECT count(*) FROM edges WHERE relation='CONTAINS'");
            int inherits = scalar(st, "SELECT count(*) FROM edges WHERE relation='INHERITS'");
            int impls = scalar(st, "SELECT count(*) FROM edges WHERE relation='IMPLEMENTS'");
            int overrides = scalar(st, "SELECT count(*) FROM edges WHERE relation='OVERRIDES'");
            int callsAll = scalar(st, "SELECT count(*) FROM edges WHERE relation='CALLS'");
            int callsInternal = scalar(st,
                    "SELECT count(*) FROM edges WHERE relation='CALLS' AND is_external=0");
            int references = scalar(st, "SELECT count(*) FROM edges WHERE relation='REFERENCES'");
            int annotations = scalar(st,
                    "SELECT count(*) FROM annotations");
            int overrideAnnotations = scalar(st,
                    "SELECT count(*) FROM annotations WHERE annotation_fqn='java.lang.Override'");
            int orderServiceHits = scalar(st,
                    "SELECT count(*) FROM nodes WHERE qualified_name='com.example.shop.service.OrderService'");
            int fts = scalar(st,
                    "SELECT count(*) FROM node_names WHERE label MATCH 'OrderService'");

            assertTrue(classes >= 4, "expected ≥4 CLASS nodes; got " + classes);
            assertTrue(methods >= 1, "expected ≥1 METHOD; got " + methods);
            assertTrue(fields >= 4, "expected ≥4 FIELD nodes; got " + fields);
            assertTrue(contains > 46, "CONTAINS should grow beyond Phase-1-MVP baseline 46; got " + contains);
            assertTrue(inherits >= 1, "expected ≥1 INHERITS (OrderService extends BaseService); got " + inherits);
            assertTrue(impls >= 1, "expected ≥1 IMPLEMENTS (InMemoryOrderRepository); got " + impls);
            assertTrue(overrides >= 1, "expected ≥1 OVERRIDES; got " + overrides);
            assertTrue(callsAll >= 3, "expected ≥3 CALLS in createOrder; got " + callsAll);
            assertTrue(callsInternal >= 1, "expected ≥1 internal CALLS; got " + callsInternal);
            assertTrue(references >= 1, "expected ≥1 REFERENCES; got " + references);
            assertTrue(annotations >= 1, "expected ≥1 annotation row; got " + annotations);
            assertTrue(overrideAnnotations >= 1,
                    "expected ≥1 @Override (JDK-internal so resolvable even with --no-classpath); got " + overrideAnnotations);
            assertEquals(1, orderServiceHits, "OrderService node missing or duplicated");
            assertTrue(fts >= 1, "FTS5 should match OrderService; got " + fts);
        }
    }

    private static int scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
