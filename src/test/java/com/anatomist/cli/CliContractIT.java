package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.test.CliTestSupport;
import com.anatomist.test.CliTestSupport.RunResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliContractIT {

    @TempDir Path tmp;
    Path project;
    Path db;

    @BeforeEach
    void indexProject() throws Exception {
        project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Files.writeString(project.resolve("src/main/java/p/Marked.java"), """
                package p;
                @Deprecated class MarkedOne {}
                @Deprecated class MarkedTwo {}
                """);
        db = tmp.resolve("contracts.db");
        CliTestSupport.assertIndexOk(project, "--no-classpath", "--java-version", "17",
                "--output", db.toString(), "--format", "json");
    }

    @Test
    void annotationCountUsesAnnotationMode() throws Exception {
        RunResult rows = run("search", "Deprecated", "--by-annotation");
        RunResult count = run("search", "Deprecated", "--by-annotation", "--count");
        assertEquals(0, rows.exitCode(), rows.stderr());
        assertEquals(0, count.exitCode(), count.stderr());
        assertEquals(total(rows.stdout()), total(count.stdout()));
        assertEquals(2, total(count.stdout()));
    }

    @Test
    void invalidValuesExitTwoWithoutStackTrace() throws Exception {
        for (String[] args : new String[][] {
                {"search", "--by-annotation"},
                {"search", "A", "--kind", "TYPO"},
                {"resolve", "p.A", "--scope", "TYPO"},
                {"calls", "--direction", "typo"},
                {"overview", "--format", "yaml"},
                {"doctor", "--format", "yaml"}
        }) {
            RunResult result = run(args);
            assertEquals(2, result.exitCode(), String.join(" ", args)
                    + "\n" + result.stdout() + result.stderr());
            assertTrue(result.stderr().contains("ERROR:"), result.stderr());
            assertFalse(result.stderr().contains("Exception"), result.stderr());
            assertFalse(result.stderr().contains("\tat "), result.stderr());
        }
    }

    @Test
    void semanticTableAndAnnotateJsonHonorFormats() throws Exception {
        RunResult search = run("search", "p.A", "--format", "table");
        assertEquals(0, search.exitCode(), search.stderr());
        assertTrue(search.stdout().startsWith("RECORD\tID"), search.stdout());
        RunResult annotate = run("annotate", "p.A", "--category", "REVIEWED",
                "--format", "json");
        assertEquals(0, annotate.exitCode(), annotate.stderr());
        Map<?, ?> json = asMap(annotate.stdout());
        assertEquals("ok", json.get("status"));
        assertEquals(1, ((Number) json.get("annotated")).intValue());
    }

    @Test
    void indexRejectsUnknownFormatBeforeWriting() throws Exception {
        Path output = tmp.resolve("invalid-format.db");
        RunResult result = CliTestSupport.runIndex(project, "--format", "yaml",
                "--output", output.toString());
        assertEquals(2, result.exitCode(), result.stderr());
        assertFalse(Files.exists(output));
        assertFalse(result.stderr().contains("Exception"), result.stderr());
    }

    private RunResult run(String... args) throws Exception {
        String[] command = java.util.Arrays.copyOf(args, args.length + 2);
        command[args.length] = "--index";
        command[args.length + 1] = db.toString();
        return CliTestSupport.capture(() -> new CommandLine(new AnatomistCli()).execute(command));
    }

    private static int total(String json) {
        List<?> records = records(json);
        for (Object value : records) {
            Map<?, ?> record = (Map<?, ?>) value;
            if (record.get("count") instanceof Number count) return count.intValue();
        }
        return (int) records.stream().map(value -> (Map<?, ?>) value)
                .filter(record -> "entity_candidate".equals(record.get("record"))).count();
    }

    private static List<?> records(String output) {
        String trimmed = output.trim();
        try {
            Object parsed = Json.parseTree(trimmed);
            return parsed instanceof List<?> list ? list : List.of(parsed);
        } catch (IllegalArgumentException multipleRecords) {
            return trimmed.lines().filter(line -> !line.isBlank()).map(Json::parseTree).toList();
        }
    }

    private static Map<?, ?> asMap(String json) {
        return (Map<?, ?>) Json.parseTree(json);
    }
}
