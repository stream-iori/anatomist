package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.query.EdgeRow;
import com.anatomist.query.QueryService;
import com.anatomist.query.TraversalResult;
import com.anatomist.test.CliTestSupport;
import com.anatomist.test.CliTestSupport.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TraversalDisclosureIT {

    @Test
    void queryApiReportsExactForwardReverseAndPathDepthBoundaries(@TempDir Path tmp)
            throws Exception {
        Path db = buildCallChain(tmp);

        try (QueryService query = new QueryService(db)) {
            TraversalResult<EdgeRow> shallow = query.calleesTraversal("p.A#a", 2, false);
            assertEquals(2, shallow.reachedDepth());
            assertTrue(shallow.depthTruncated());
            assertTrue(shallow.frontierCount() > 0);

            TraversalResult<EdgeRow> complete = query.calleesTraversal("p.A#a", 4, false);
            assertFalse(complete.depthTruncated());
            assertEquals(0, complete.frontierCount());

            TraversalResult<EdgeRow> reverse = query.callersTraversal("p.A#d", 2, false);
            assertTrue(reverse.depthTruncated());

            TraversalResult<EdgeRow> missing = query.callPathTraversal(
                    "p.A#a", "p.A#d", 2, false);
            assertTrue(missing.items().isEmpty());
            assertTrue(missing.depthTruncated());

            TraversalResult<EdgeRow> found = query.callPathTraversal(
                    "p.A#a", "p.A#d", 3, false);
            assertEquals(3, found.items().size());
            assertFalse(found.depthTruncated());
        }
    }

    @Test
    void cliSeparatesPageAndDepthContinuationAndMarksEmptyPathIndeterminate(
            @TempDir Path tmp) throws Exception {
        Path db = buildCallChain(tmp);

        RunResult callees = runCli("callees-of", "p.A#a", "--depth", "1",
                "--limit", "1", "--index", db.toString());
        assertEquals(0, callees.exitCode(), callees.stderr());
        Map<?, ?> output = object(callees.stdout());
        Map<?, ?> stats = (Map<?, ?>) output.get("stats");
        assertEquals(Boolean.TRUE, stats.get("truncated"));
        assertEquals(Boolean.TRUE, stats.get("depth_truncated"));
        List<?> next = (List<?>) output.get("next_queries");
        assertEquals(2, next.size(), next.toString());
        assertTrue(next.get(0).toString().contains("--offset 1"), next.toString());
        assertTrue(next.get(1).toString().contains("--depth 2"), next.toString());
        assertFalse((Boolean) ((Map<?, ?>) output.get("evidence"))
                .get("negative_conclusion_safe"));

        RunResult branches = runCli("branches-of", "p.A#a", "--depth", "1",
                "--index", db.toString());
        assertEquals(0, branches.exitCode(), branches.stderr());
        Map<?, ?> branchStats = (Map<?, ?>) object(branches.stdout()).get("stats");
        assertEquals(Boolean.TRUE, branchStats.get("depth_truncated"));

        RunResult path = runCli("call-path", "p.A#a", "p.A#d", "--depth", "2",
                "--index", db.toString());
        assertEquals(2, path.exitCode(), path.stderr());
        Map<?, ?> pathOutput = object(path.stdout());
        assertEquals(Boolean.TRUE, ((Map<?, ?>) pathOutput.get("stats")).get("depth_truncated"));
        Map<?, ?> evidence = (Map<?, ?>) pathOutput.get("evidence");
        assertEquals("indeterminate", evidence.get("status"));
        assertEquals("QUERY_DEPTH_TRUNCATED", evidence.get("code"));
        assertEquals(Boolean.FALSE, evidence.get("negative_conclusion_safe"));
    }

    @Test
    void hardDepthCapIsDisclosedWithoutAnInvalidContinuation(@TempDir Path tmp)
            throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        StringBuilder source = new StringBuilder("package p; class Deep {\n");
        for (int i = 0; i < 22; i++) {
            source.append("void m").append(i).append("() {");
            if (i < 21) source.append("m").append(i + 1).append("();");
            source.append("}\n");
        }
        source.append("}\n");
        Files.writeString(project.resolve("src/main/java/p/A.java"), source);
        Path db = tmp.resolve("deep.db");
        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--java-version", "17",
                "--output", db.toString(), "--format", "json");

        RunResult result = runCli("callees-of", "p.Deep#m0", "--depth", "100",
                "--limit", "0", "--index", db.toString());
        assertEquals(0, result.exitCode(), result.stderr());
        Map<?, ?> output = object(result.stdout());
        Map<?, ?> stats = (Map<?, ?>) output.get("stats");
        assertEquals(20, ((Number) stats.get("depth_effective")).intValue());
        assertEquals(Boolean.TRUE, stats.get("depth_truncated"));
        assertEquals(Boolean.TRUE, stats.get("depth_limit_reached"));
        assertNull(output.get("next_queries"));
    }

    private static Path buildCallChain(Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Files.writeString(project.resolve("src/main/java/p/A.java"), """
                package p;
                class A {
                    void a() { b(); helper(); }
                    void b() { if (true) { c(); } }
                    void c() { d(); }
                    void d() {}
                    void helper() {}
                }
                """);
        Path db = tmp.resolve("chain.db");
        CliTestSupport.assertIndexOk(project,
                "--no-classpath", "--java-version", "17",
                "--output", db.toString(), "--format", "json");
        return db;
    }

    private static RunResult runCli(String... args) throws Exception {
        return CliTestSupport.capture(() -> new CommandLine(new AnatomistCli()).execute(args));
    }

    private static Map<?, ?> object(String json) {
        Object tree = Json.parseTree(json);
        assertInstanceOf(Map.class, tree);
        return (Map<?, ?>) tree;
    }
}
