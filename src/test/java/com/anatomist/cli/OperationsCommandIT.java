package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.test.CliTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OperationsCommandIT {
    @Test
    void exposesAllOperationsWithoutOpeningAnIndex() throws Exception {
        CliTestSupport.RunResult result = CliTestSupport.capture(() ->
                new CommandLine(new AnatomistCli()).execute("operations", "--format", "json"));
        assertEquals(0, result.exitCode(), result.stderr());
        Map<?, ?> root = object(result.stdout());
        assertEquals("anatomist-operation-catalog/v1", root.get("contract"));
        List<?> operations = (List<?>) root.get("operations");
        assertEquals(20, operations.size());
        assertTrue(operations.stream().map(Map.class::cast)
                .anyMatch(value -> "calls".equals(value.get("id"))));
        assertFalse(result.stdout().contains("recipe"));
    }

    @Test
    void filtersLanguageWithoutClaimingAnUninstalledProvider() throws Exception {
        CliTestSupport.RunResult result = CliTestSupport.capture(() ->
                new CommandLine(new AnatomistCli()).execute("operations", "calls",
                        "--language", "python"));
        assertEquals(0, result.exitCode(), result.stderr());
        Map<?, ?> root = object(result.stdout());
        Map<?, ?> operation = (Map<?, ?>) ((List<?>) root.get("operations")).getFirst();
        Map<?, ?> support = (Map<?, ?>) ((List<?>) operation.get("support")).getFirst();
        assertEquals("unsupported", support.get("support"));
        assertEquals("PROVIDER_NOT_INSTALLED", support.get("reason"));
        assertFalse(support.containsKey("limitations"));
        assertTrue(((List<?>) operation.get("constraints")).stream()
                .map(Map.class::cast)
                .anyMatch(value -> "--direction".equals(value.get("option"))
                        && ((List<?>) value.get("values")).contains("incoming")));
    }

    @Test
    void indexAvailabilityInspectionIsReadOnly(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Path db = tmp.resolve("catalog.db");
        CliTestSupport.assertIndexOk(project, "--no-classpath", "--output", db.toString());
        byte[] before = Files.readAllBytes(db);
        CliTestSupport.RunResult result = CliTestSupport.capture(() ->
                new CommandLine(new AnatomistCli()).execute("operations", "calls",
                        "--index", db.toString()));
        assertEquals(0, result.exitCode(), result.stderr());
        Map<?, ?> root = object(result.stdout());
        Map<?, ?> operation = (Map<?, ?>) ((List<?>) root.get("operations")).getFirst();
        Map<?, ?> support = (Map<?, ?>) ((List<?>) operation.get("support")).getFirst();
        assertEquals("available", support.get("availability"));
        assertArrayEquals(before, Files.readAllBytes(db));
    }

    private static Map<?, ?> object(String json) {
        return (Map<?, ?>) Json.parseTree(json);
    }
}
