package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.query.JsonFormatter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L3 golden-file driver: each subdir under {@code tests/scenarios/} contains
 * an {@code input.cmd} (one CLI command, args separated by whitespace) and an
 * {@code expected.json}. We run the command against a freshly-built index of
 * {@code fixtures/mini-spring-shop}, capture stdout, normalize, and assert the
 * normalized JSON matches expected.json.
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
                  .filter(p -> Files.isRegularFile(p.resolve("input.cmd")))
                  .forEach(dirs::add);
        }
        Collections.sort(dirs);
        return dirs.stream().map(d -> DynamicTest.dynamicTest(
                d.getFileName().toString(), () -> runScenario(d)));
    }

    private void runScenario(Path scenarioDir) throws Exception {
        String inputCmd = Files.readString(scenarioDir.resolve("input.cmd"),
                StandardCharsets.UTF_8).trim();
        // Allow comments / blank lines
        StringBuilder joined = new StringBuilder();
        for (String line : inputCmd.split("\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            if (joined.length() > 0) joined.append(' ');
            joined.append(s);
        }
        String[] argv = tokenize(joined.toString());
        // Auto-inject --index pointing at our built db.
        List<String> args = new ArrayList<>();
        Collections.addAll(args, argv);
        if (!args.contains("--index")) {
            args.add("--index");
            args.add(dbPath.toString());
        }

        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        PrintStream old = System.out;
        int rc;
        try {
            System.setOut(new PrintStream(cap, true, StandardCharsets.UTF_8));
            rc = new CommandLine(new AnatomistCli()).execute(args.toArray(new String[0]));
        } finally {
            System.setOut(old);
        }
        assertEquals(0, rc, "command exited non-zero: " + inputCmd
                + "\nstdout:\n" + cap.toString(StandardCharsets.UTF_8));

        String actualJson = normalize(cap.toString(StandardCharsets.UTF_8));
        Path expected = scenarioDir.resolve("expected.json");

        if (UPDATE || !Files.exists(expected)) {
            Files.writeString(expected, actualJson, StandardCharsets.UTF_8);
            if (!UPDATE) fail("expected.json was missing — generated from actual run; "
                    + "review " + expected + " then re-run");
            return;
        }

        String expectedJson = normalize(Files.readString(expected, StandardCharsets.UTF_8));
        if (!expectedJson.equals(actualJson)) {
            fail("golden mismatch for " + scenarioDir.getFileName()
                    + "\n--- expected ---\n" + expectedJson
                    + "\n--- actual ---\n" + actualJson
                    + "\n(run with -Dgolden.update=true to refresh)");
        }
    }

    /** Re-emit JSON with sorted map keys and project-root scrubbed. */
    private String normalize(String raw) {
        String scrubbed = raw.replace(repoRoot.toString(), "${PROJECT}")
                .replace(dbPath.toString(), "${INDEX}");
        return Json.writeCanonical(Json.parseTree(scrubbed));
    }

    /** Minimal shell-like tokenizer — supports double-quoted segments. */
    private static String[] tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') { inQuote = !inQuote; continue; }
            if (!inQuote && Character.isWhitespace(c)) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
