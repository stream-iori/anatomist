package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.test.CliTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PipelineCommandIT {
    private static Path db;

    @BeforeAll
    static void buildIndex(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Files.writeString(project.resolve("src/main/java/p/AnotherA.java"),
                "package p; class AnotherA {}\n", StandardCharsets.UTF_8);
        db = tmp.resolve("pipeline.db");
        CliTestSupport.assertIndexOk(project, "--no-classpath", "--output", db.toString());
    }

    @Test
    void inlineAndFilePipelinesMatchShellBytes(@TempDir Path tmp) throws Exception {
        RunResult resolved = run(new byte[0], "resolve", "p.A", "--kind", "type",
                "--exact", "--unique", "--index", db.toString());
        assertEquals(0, resolved.exitCode, resolved.stderr);
        RunResult shell = run(resolved.stdout, "describe", "--index", db.toString());
        assertEquals(0, shell.exitCode, shell.stderr);

        RunResult inline = run(new byte[0], "pipeline", "--index", db.toString(), "--",
                "resolve", "p.A", "--kind", "type", "--exact", "--unique",
                "--then", "describe");
        assertEquals(0, inline.exitCode, inline.stderr);
        assertArrayEquals(shell.stdout, inline.stdout);

        Path spec = tmp.resolve("pipeline.json");
        Files.writeString(spec, """
                {"stages":[
                  ["resolve","p.A","--kind","type","--exact","--unique"],
                  ["describe"]
                ]}
                """, StandardCharsets.UTF_8);
        RunResult file = run(new byte[0], "pipeline", "--index", db.toString(),
                "--file", spec.toString());
        assertEquals(0, file.exitCode, file.stderr);
        assertArrayEquals(shell.stdout, file.stdout);
    }

    @Test
    void firstStageCanConsumeSemanticStdin() throws Exception {
        RunResult resolved = run(new byte[0], "resolve", "p.A", "--kind", "type",
                "--exact", "--unique", "--index", db.toString());
        RunResult shell = run(resolved.stdout, "describe", "--index", db.toString());
        RunResult fused = run(resolved.stdout, "pipeline", "--index", db.toString(),
                "--", "describe");
        assertEquals(0, fused.exitCode, fused.stderr);
        assertArrayEquals(shell.stdout, fused.stdout);
    }

    @Test
    void preservesShellEvidenceWhenAnEarlierStageIsTruncated() throws Exception {
        RunResult searched = run(new byte[0], "search", "--name", "*A*", "--kind",
                "type", "--limit", "1", "--index", db.toString());
        assertTrue(new String(searched.stdout, StandardCharsets.UTF_8)
                .contains("\"truncated\":true"));
        RunResult shell = run(searched.stdout, "resolve", "--index", db.toString());
        RunResult fused = run(new byte[0], "pipeline", "--index", db.toString(), "--",
                "search", "--name", "*A*", "--kind", "type", "--limit", "1",
                "--then", "resolve");
        assertEquals(0, fused.exitCode, fused.stderr);
        assertArrayEquals(shell.stdout, fused.stdout);
    }

    @Test
    void rejectsUnknownWriteCommonOptionsAndIncompatibleStages() throws Exception {
        assertPipelineError(run(new byte[0], "pipeline", "--index", db.toString(),
                        "--", "missing-command"),
                "PIPELINE_INVALID_SPEC", 1, "missing-command");
        assertPipelineError(run(new byte[0], "pipeline", "--index", db.toString(),
                        "--", "index"),
                "PIPELINE_INVALID_SPEC", 1, "index");
        assertPipelineError(run(new byte[0], "pipeline", "--index", db.toString(),
                        "--", "search", "A", "--index", db.toString()),
                "PIPELINE_INVALID_SPEC", 1, "search");
        assertPipelineError(run(new byte[0], "pipeline", "--index", db.toString(),
                        "--", "search", "A", "--count", "--then", "resolve"),
                "PIPELINE_INCOMPATIBLE_STAGES", 2, "resolve");
    }

    @Test
    void validatesSpecShapeAndLimits(@TempDir Path tmp) throws Exception {
        Path spec = tmp.resolve("pipeline.json");
        Files.writeString(spec, "{\"stages\":[]}", StandardCharsets.UTF_8);
        assertPipelineError(run(new byte[0], "pipeline", "--index", db.toString(),
                        "--file", spec.toString()),
                "PIPELINE_INVALID_SPEC", null, null);
        assertPipelineError(run(new byte[0], "pipeline", "--index", db.toString(),
                        "--", "search", "A", "--then"),
                "PIPELINE_INVALID_SPEC", null, null);
        assertPipelineError(run(new byte[0], "pipeline", "--index", db.toString(),
                        "--file", spec.toString(), "--", "search", "A"),
                "PIPELINE_INVALID_SPEC", null, null);

        List<String> tooMany = new ArrayList<>(List.of(
                "pipeline", "--index", db.toString(), "--"));
        for (int i = 0; i < 17; i++) {
            if (i > 0) tooMany.add("--then");
            tooMany.add("search");
            tooMany.add("A");
        }
        assertPipelineError(run(new byte[0], tooMany.toArray(String[]::new)),
                "PIPELINE_LIMIT_EXCEEDED", null, null);
    }

    @Test
    void registryAcceptsAllTwentyReadOnlySemanticCommands() {
        List<List<String>> commands = List.of(
                List.of("search", "A"),
                List.of("resolve", "p.A"),
                List.of("describe"),
                List.of("members"),
                List.of("type-relations"),
                List.of("runtime-implementations"),
                List.of("callable-relations"),
                List.of("calls"),
                List.of("dispatch"),
                List.of("bindings"),
                List.of("annotations"),
                List.of("related-docs"),
                List.of("references"),
                List.of("accesses"),
                List.of("regions"),
                List.of("sites-in"),
                List.of("trace", "--to", "p.A#run()"),
                List.of("source"),
                List.of("declarations-of", "--file", "src/main/java/p/A.java"),
                List.of("overview"));
        assertEquals(20, commands.size());
        for (int i = 0; i < commands.size(); i++) {
            PipelineStageRegistry.Stage stage = PipelineStageRegistry.parse(
                    commands.get(i), i + 1, db, null, "MAIN", "java", "java-core");
            assertEquals(commands.get(i).getFirst(), stage.name());
            assertNotNull(stage.command());
            assertFalse(stage.emitted().isEmpty());
        }
    }

    @Test
    void explainIsStaticAndCheckIsReadOnly(@TempDir Path tmp) throws Exception {
        Path missing = tmp.resolve("missing.db");
        RunResult explained = run(new byte[0], "pipeline", "--explain", "--index",
                missing.toString(), "--", "resolve", "p.A", "--kind", "type",
                "--exact", "--unique", "--then", "describe");
        assertEquals(0, explained.exitCode, explained.stderr);
        Map<?, ?> explain = (Map<?, ?>) Json.parseTree(
                new String(explained.stdout, StandardCharsets.UTF_8));
        assertEquals("anatomist-pipeline-plan/v1", explain.get("contract"));
        assertEquals("valid", explain.get("status"));
        assertFalse(Files.exists(missing));

        byte[] before = Files.readAllBytes(db);
        RunResult checked = run(new byte[0], "pipeline", "--check", "--index",
                db.toString(), "--", "resolve", "p.A", "--kind", "type",
                "--exact", "--unique", "--then", "describe");
        assertEquals(0, checked.exitCode, checked.stderr);
        Map<?, ?> check = (Map<?, ?>) Json.parseTree(
                new String(checked.stdout, StandardCharsets.UTF_8));
        assertEquals("ready", check.get("status"));
        assertArrayEquals(before, Files.readAllBytes(db), "--check must not mutate the index");
    }

    @Test
    void stageFailureIsOneLineJsonWithCause() throws Exception {
        RunResult result = run(new byte[0], "pipeline", "--index", db.toString(), "--",
                "resolve", "p.A", "--kind", "type", "--exact", "--unique",
                "--then", "calls");
        assertPipelineError(result, "PIPELINE_STAGE_FAILED", 2, "calls");
        Map<?, ?> json = (Map<?, ?>) Json.parseTree(result.stderr.trim());
        Map<?, ?> cause = (Map<?, ?>) json.get("cause");
        assertEquals(2L, ((Number) cause.get("exit")).longValue());
        assertNotNull(cause.get("code"));
        assertEquals("anatomist-error/v1", json.get("contract"));
        assertEquals(1, result.stderr.lines().count(), result.stderr);
    }

    @Test
    void rejectsAnUninstalledInputLanguageBeforeQuerying() throws Exception {
        RunResult resolved = run(new byte[0], "resolve", "p.A", "--kind", "type",
                "--exact", "--unique", "--index", db.toString());
        String foreign = new String(resolved.stdout, StandardCharsets.UTF_8)
                .replace("\"language\":\"java\"", "\"language\":\"python\"");
        RunResult result = run(foreign.getBytes(StandardCharsets.UTF_8), "describe",
                "--index", db.toString());
        assertEquals(3, result.exitCode, result.stderr);
        Map<?, ?> error = (Map<?, ?>) Json.parseTree(result.stderr.trim());
        assertEquals("UNSUPPORTED_CAPABILITY", error.get("code"));
        assertEquals("python", ((Map<?, ?>) error.get("details")).get("language"));
        assertEquals(0, result.stdout.length);
    }

    @Test
    void brokenPipeIsSuccessful() {
        PrintStream oldOut = System.out;
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream oldErr = System.err;
        try {
            System.setOut(new PrintStream(new OutputStream() {
                @Override public void write(int value) throws IOException {
                    throw new IOException("consumer closed");
                }
            }));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            int exit = new CommandLine(new AnatomistCli()).execute(
                    "pipeline", "--index", db.toString(), "--", "search", "A");
            assertEquals(0, exit, stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private static void assertPipelineError(RunResult result, String code,
                                            Integer stage, String command) {
        assertEquals(5, result.exitCode, result.stderr);
        assertEquals(0, result.stdout.length, new String(result.stdout, StandardCharsets.UTF_8));
        Map<?, ?> json = (Map<?, ?>) Json.parseTree(result.stderr.trim());
        assertEquals(code, json.get("code"));
        if (stage != null) assertEquals(stage.longValue(),
                ((Number) ((Map<?, ?>) json.get("stage")).get("position")).longValue());
        if (command != null) assertEquals(command,
                ((Map<?, ?>) json.get("stage")).get("operation"));
        assertEquals(1, result.stderr.lines().count(), result.stderr);
    }

    private static RunResult run(byte[] input, String... args) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        InputStream oldIn = System.in;
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        try {
            System.setIn(new ByteArrayInputStream(input));
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            int exit = new CommandLine(new AnatomistCli()).execute(args);
            return new RunResult(exit, stdout.toByteArray(),
                    stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setIn(oldIn);
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private record RunResult(int exitCode, byte[] stdout, String stderr) {}
}
