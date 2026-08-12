package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.test.CliTestSupport;
import com.anatomist.test.CliTestSupport.RunResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NonFlowMavenFixtureIT {

    static Path project;
    static Path db;

    @BeforeAll
    static void indexFixture(@TempDir Path tmp) throws Exception {
        Path source = CliTestSupport.repoRoot().resolve("fixtures/maven-nonflow-edge-cases");
        project = tmp.resolve("maven-nonflow-edge-cases");
        CliTestSupport.copyDir(source, project);
        Path generated = project.resolve(
                "alpha/target/generated-sources/fixture/com/example/edge/generated/GeneratedFixture.java");
        Files.createDirectories(generated.getParent());
        Files.copy(project.resolve(
                "generated-template/com/example/edge/generated/GeneratedFixture.java"), generated);
        db = tmp.resolve("nonflow.db");
        CliTestSupport.assertIndexOk(project, "--no-classpath", "--java-version", "17",
                "--spring-xml", "--output", db.toString(), "--format", "json");
    }

    @Test
    void springRoutesExpandPathsAndMethodsWithoutCollisions() throws Exception {
        assertEquals(9, scalar("SELECT count(*) FROM nodes WHERE kind='ROUTE'"));
        assertEquals(2, scalar("SELECT count(*) FROM nodes WHERE kind='ROUTE' "
                + "AND label='GET /v1/orders'"));
        assertEquals(2, scalar("SELECT count(DISTINCT id) FROM nodes WHERE kind='ROUTE' "
                + "AND label='GET /v1/orders'"));
        assertEquals(9, scalar("SELECT count(*) FROM edges WHERE relation='HANDLES'"));
        assertEquals(2, scalar("SELECT count(*) FROM nodes WHERE kind='ROUTE' "
                + "AND symbol_id LIKE 'route:GET /v1/orders|handler:%EdgeController#handle()'"));
    }

    @Test
    void springSingleConstructorAndQualifierAreIndexed() throws Exception {
        assertEquals(1, scalar("SELECT count(*) FROM edges e "
                + "JOIN nodes s ON s.id=e.source_id JOIN nodes t ON t.id=e.target_id "
                + "WHERE e.relation='INJECTS' "
                + "AND s.symbol_id='com.example.edge.alpha.CheckoutService' "
                + "AND t.symbol_id='com.example.edge.alpha.Gateway' "
                + "AND e.metadata LIKE '%primaryGateway%' "
                + "AND e.metadata LIKE '%implicitSingleConstructor%'"));
        assertEquals(0, scalar("SELECT count(*) FROM edges e JOIN nodes s ON s.id=e.source_id "
                + "WHERE e.relation='INJECTS' "
                + "AND s.symbol_id='com.example.edge.alpha.MultipleConstructors'"));
    }

    @Test
    void beanConfigPagesAndEscapesLiteralWildcards() throws Exception {
        RunResult first = run("bean-config", "pagedBean", "--format", "json",
                "--module", "alpha", "--limit", "10");
        assertEquals(0, first.exitCode(), first.stdout() + first.stderr());
        Map<?, ?> firstJson = asMap(first.stdout());
        Map<?, ?> stats = (Map<?, ?>) firstJson.get("stats");
        assertEquals(24, ((Number) stats.get("total")).intValue());
        assertEquals(10, ((List<?>) firstJson.get("results")).size());
        assertEquals(true, stats.get("truncated"));
        assertFalse(((List<?>) firstJson.get("next_queries")).isEmpty());

        RunResult last = run("bean-config", "pagedBean", "--format", "json",
                "--module", "alpha", "--limit", "10", "--offset", "20");
        assertEquals(0, last.exitCode(), last.stdout() + last.stderr());
        assertEquals(4, ((List<?>) asMap(last.stdout()).get("results")).size());

        RunResult literal = run("bean-config", "literal%_", "--format", "json",
                "--module", "alpha");
        assertEquals(0, literal.exitCode(), literal.stdout() + literal.stderr());
        List<?> results = (List<?>) asMap(literal.stdout()).get("results");
        assertEquals(1, results.size());
        assertTrue(literal.stdout().contains("literal%_bean"), literal.stdout());
        assertFalse(literal.stdout().contains("literalXYZbean"), literal.stdout());
    }

    @Test
    void generatedTemplateIsIndexedWithMavenModuleAndScope() throws Exception {
        assertEquals(1, scalar("SELECT count(*) FROM nodes "
                + "WHERE symbol_id='com.example.edge.generated.GeneratedFixture' "
                + "AND module='alpha' AND scope='GENERATED'"));
    }

    private static int scalar(String sql) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db);
             var st = c.createStatement();
             var rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static RunResult run(String... args) throws Exception {
        String[] command = java.util.Arrays.copyOf(args, args.length + 2);
        command[args.length] = "--index";
        command[args.length + 1] = db.toString();
        return CliTestSupport.capture(() -> new CommandLine(new AnatomistCli()).execute(command));
    }

    private static Map<?, ?> asMap(String json) {
        return (Map<?, ?>) Json.parseTree(json);
    }
}
