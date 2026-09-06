package com.anatomist.cli;

import com.anatomist.json.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L3 golden-file driver: each subdir under {@code tests/scenarios/} contains
 * a {@code pipeline.json} (stages are argv arrays) and an
 * optional {@code expected.exit}, {@code expected.json}, and
 * {@code expected.stderr} files. We run the command against a freshly-built
 * index of {@code fixtures/mini-spring-shop} and compare both output streams.
 *
 * <p>Run with {@code -Dgolden.update=true} to regenerate expected.json from
 * the actual output (use after intentional output-shape changes).</p>
 *
 * <p>Normalization: project-absolute paths are rewritten to
 * {@code ${PROJECT}/...} so tests survive moving the repo.</p>
 */
class GoldenFileIT {

    private static final boolean UPDATE = Boolean.getBoolean("golden.update");

    private static Path repoRoot;
    private static Path fixture;
    private static Path dbPath;
    private static Path scenariosDir;

    @BeforeAll
    static void buildOnce(@TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER) Path tmp)
            throws Exception {
        repoRoot = Path.of(System.getProperty("user.dir"));
        fixture = repoRoot.resolve("fixtures/mini-spring-shop");
        scenariosDir = repoRoot.resolve("tests/scenarios");
        assertTrue(Files.isDirectory(fixture));
        if (!Files.isDirectory(scenariosDir)) {
            // Nothing to do — TestFactory will produce an empty stream.
            return;
        }

        dbPath = tmp.resolve("golden-index.db");
        String projectSource = String.join(File.pathSeparator,
                fixture.resolve("api/src/main/java").toString(),
                fixture.resolve("domain/src/main/java").toString(),
                fixture.resolve("service/src/main/java").toString());

        IndexCommand cmd = new IndexCommand();
        new CommandLine(cmd).parseArgs(
                fixture.toString(),
                "--project-source", projectSource,
                "--no-classpath",
                "--output", dbPath.toString());

        // swallow the verbose index log
        PrintStream old = System.out;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            assertEquals(0, cmd.call(), "index failed");
        } finally {
            System.setOut(old);
        }
    }

    @TestFactory
    Stream<DynamicTest> goldenScenarios() throws Exception {
        if (scenariosDir == null || !Files.isDirectory(scenariosDir)) return Stream.empty();
        List<Path> dirs = new ArrayList<>();
        try (var stream = Files.list(scenariosDir)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> Files.isRegularFile(p.resolve("pipeline.json")))
                  .forEach(dirs::add);
        }
        Collections.sort(dirs);
        return dirs.stream().map(d -> DynamicTest.dynamicTest(
                d.getFileName().toString(), () -> runScenario(d)));
    }

    private void runScenario(Path scenarioDir) throws Exception {
        Object rawPipeline = Json.parseTree(Files.readString(
                scenarioDir.resolve("pipeline.json"), StandardCharsets.UTF_8));
        assertInstanceOf(java.util.Map.class, rawPipeline);
        Object rawStages = ((java.util.Map<?, ?>) rawPipeline).get("stages");
        assertInstanceOf(List.class, rawStages);
        List<?> stages = (List<?>) rawStages;
        assertFalse(stages.isEmpty(), "pipeline must contain at least one stage");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        InputStream oldIn = System.in;
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        int rc = 0;
        byte[] input = new byte[0];
        try {
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            for (Object rawStage : stages) {
                assertInstanceOf(List.class, rawStage);
                List<String> args = ((List<?>) rawStage).stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                if (!args.contains("--index")) {
                    args.add("--index"); args.add(dbPath.toString());
                }
                stdout = new ByteArrayOutputStream();
                System.setIn(new ByteArrayInputStream(input));
                System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
                rc = new CommandLine(new AnatomistCli()).execute(args.toArray(String[]::new));
                input = stdout.toByteArray();
                if (rc != 0) break;
            }
        } finally {
            System.setIn(oldIn);
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
        Path expectedExitFile = scenarioDir.resolve("expected.exit");
        int expectedExit = Files.exists(expectedExitFile)
                ? Integer.parseInt(Files.readString(expectedExitFile, StandardCharsets.UTF_8).trim())
                : 0;
        if (UPDATE && (rc != 0 || Files.exists(expectedExitFile))) {
            Files.writeString(expectedExitFile, rc + "\n", StandardCharsets.UTF_8);
            expectedExit = rc;
        }
        assertEquals(expectedExit, rc, "unexpected exit: " + stages
                + "\nstdout:\n" + stdout.toString(StandardCharsets.UTF_8)
                + "\nstderr:\n" + stderr.toString(StandardCharsets.UTF_8));

        if (rc == 0) {
            byte[] fused = runFused(scenarioDir.resolve("pipeline.json"));
            assertArrayEquals(input, fused,
                    "fused pipeline stdout must be byte-identical: "
                            + scenarioDir.getFileName());
            assertCanonicalFraming(input, scenarioDir.getFileName().toString());
            if ("B2-declarations-of".equals(scenarioDir.getFileName().toString())) {
                assertTrue(input.length <= 6_203,
                        "declarations output exceeds 1.6x the 0.14 3,877-byte baseline: "
                                + input.length);
            }
        }

        String rawStdout = new String(input, StandardCharsets.UTF_8);
        Path expected = scenarioDir.resolve("expected.json");
        if (!rawStdout.isBlank() || Files.exists(expected)) {
            String actualJson = normalize(rawStdout);
            if (UPDATE || !Files.exists(expected)) {
                Files.writeString(expected, actualJson, StandardCharsets.UTF_8);
                if (!UPDATE) fail("expected.json was missing — generated from actual run; "
                        + "review " + expected + " then re-run");
            } else {
                String expectedJson = normalize(Files.readString(expected, StandardCharsets.UTF_8));
                if (!expectedJson.equals(actualJson)) {
                    fail("golden mismatch for " + scenarioDir.getFileName()
                            + "\n--- expected ---\n" + expectedJson
                            + "\n--- actual ---\n" + actualJson
                            + "\n(run with -Dgolden.update=true to refresh)");
                }
            }
        }

        String actualStderr = normalizeText(stderr.toString(StandardCharsets.UTF_8));
        Path expectedStderr = scenarioDir.resolve("expected.stderr");
        if (UPDATE && (!actualStderr.isBlank() || Files.exists(expectedStderr))) {
            Files.writeString(expectedStderr, actualStderr, StandardCharsets.UTF_8);
        }
        String wantedStderr = Files.exists(expectedStderr)
                ? normalizeText(Files.readString(expectedStderr, StandardCharsets.UTF_8)) : "";
        if (!wantedStderr.equals(actualStderr)) {
            fail("stderr mismatch for " + scenarioDir.getFileName()
                    + "\n--- expected ---\n" + wantedStderr
                    + "\n--- actual ---\n" + actualStderr);
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertCanonicalFraming(byte[] output, String scenario) {
        List<java.util.Map<String, Object>> records = new ArrayList<>();
        for (String line : new String(output, StandardCharsets.UTF_8).lines().toList()) {
            if (!line.isBlank()) records.add((java.util.Map<String, Object>) Json.parseTree(line));
        }
        assertEquals(1, records.stream()
                .filter(row -> "stream_header".equals(row.get("record"))).count(), scenario);
        assertEquals("stream_header", records.getFirst().get("record"), scenario);
        assertEquals("stream", records.getLast().get("scope"), scenario);
        Set<String> dataSeeds = records.stream()
                .filter(row -> !List.of("stream_header", "evidence").contains(row.get("record")))
                .map(row -> String.valueOf(row.get("seed_id")))
                .collect(java.util.stream.Collectors.toSet());
        Set<String> evidenceSeeds = records.stream()
                .filter(row -> "evidence".equals(row.get("record")))
                .filter(row -> "seed".equals(row.get("scope")))
                .map(row -> String.valueOf(row.get("seed_id")))
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(evidenceSeeds.containsAll(dataSeeds), scenario + " seed evidence mismatch");
        long seedEvidenceCount = records.stream()
                .filter(row -> "evidence".equals(row.get("record")))
                .filter(row -> "seed".equals(row.get("scope")))
                .count();
        assertEquals(seedEvidenceCount, evidenceSeeds.size(),
                scenario + " contains duplicate seed evidence");
        records.subList(1, records.size()).forEach(row -> {
            assertFalse(row.containsKey("contract"), scenario);
            assertFalse(row.containsKey("index_revision_id"), scenario);
            assertFalse(row.containsKey("parent_seed_id"), scenario);
        });
    }

    private byte[] runFused(Path spec) {
        List<String> args = List.of("pipeline", "--index", dbPath.toString(),
                "--file", spec.toString());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        InputStream oldIn = System.in;
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        int exit;
        try {
            System.setIn(new ByteArrayInputStream(new byte[0]));
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            exit = new CommandLine(new AnatomistCli()).execute(args.toArray(String[]::new));
        } finally {
            System.setIn(oldIn);
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
        assertEquals(0, exit, "fused pipeline failed: " + args
                + "\nstdout:\n" + stdout.toString(StandardCharsets.UTF_8)
                + "\nstderr:\n" + stderr.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8),
                "successful fused pipeline must not write stderr");
        return stdout.toByteArray();
    }

    /** Re-emit JSON with sorted map keys and project-root scrubbed. */
    private String normalize(String raw) {
        String scrubbed = normalizeText(raw);
        String trimmed = scrubbed.trim();
        Object parsed;
        try {
            parsed = Json.parseTree(trimmed);
        } catch (IllegalArgumentException multipleRecords) {
            List<Object> records = new ArrayList<>();
            for (String line : trimmed.split("\\R")) {
                if (!line.isBlank()) records.add(Json.parseTree(line));
            }
            parsed = records;
        }
        scrubVolatile(parsed);
        return Json.writeCanonical(parsed);
    }

    @SuppressWarnings("unchecked")
    private static void scrubVolatile(Object value) {
        if (value instanceof java.util.Map<?, ?> raw) {
            java.util.Map<String, Object> map = (java.util.Map<String, Object>) raw;
            if (map.containsKey("index_revision_id")) map.put("index_revision_id", "${REVISION}");
            map.values().forEach(GoldenFileIT::scrubVolatile);
        } else if (value instanceof List<?> list) list.forEach(GoldenFileIT::scrubVolatile);
    }

    private String normalizeText(String raw) {
        return raw.replace("\r\n", "\n")
                .replace(repoRoot.toString(), "${PROJECT}")
                .replace(dbPath.toString(), "${INDEX}");
    }

}
