package com.anatomist.quality;

import com.anatomist.json.Json;
import com.anatomist.test.CliTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pinned, hand-reviewed real-project call target quality gate. */
@Tag("quality-real")
class CommonsLangResolutionQualityIT {
    private static final String COMMIT = "105a350b154eac7c3f3a6bac94ec2cfb0fbe232b";

    @Test
    void precisionAndRecallMeetGate(@TempDir Path tmp) throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        Path fixture = root.resolve("fixtures/external/commons-lang");
        Path manifest = root.resolve("tests/quality/commons-lang-3.12.0-calls.json");
        assertTrue(Files.isDirectory(fixture.resolve("src/main/java")),
                "commons-lang submodule is required; run git submodule update --init");
        assertEquals(COMMIT, git(fixture, "rev-parse", "HEAD"));
        assertEquals("", git(fixture, "status", "--porcelain"),
                "quality fixture must be clean");

        @SuppressWarnings("unchecked")
        Map<String, Object> truth = (Map<String, Object>) Json.parseTree(Files.readString(manifest));
        assertEquals("anatomist-resolution-truth/v1", truth.get("contract"));
        assertEquals(COMMIT, truth.get("fixture_commit"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) truth.get("cases");
        assertEquals(48, cases.size());
        assertEquals(12, cases.stream().map(row -> row.get("file")).distinct().count());
        assertEquals(6, cases.stream().map(row -> row.get("category")).distinct().count());

        Path db = tmp.resolve("commons-lang-quality.db");
        CliTestSupport.assertIndexOk(fixture, "--project-source",
                fixture.resolve("src/main/java").toString(), "--no-classpath",
                "--java-version", "8", "--output", db.toString(), "--format", "json");

        Set<String> expected = new LinkedHashSet<>();
        Set<String> actual = new LinkedHashSet<>();
        Map<String, Set<String>> expectedByCategory = new LinkedHashMap<>();
        Map<String, Set<String>> actualByCategory = new LinkedHashMap<>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            for (Map<String, Object> item : cases) {
                String caseId = String.valueOf(item.get("id"));
                String category = String.valueOf(item.get("category"));
                @SuppressWarnings("unchecked")
                List<String> targets = (List<String>) item.get("expected_targets");
                for (String target : targets) {
                    String fact = caseId + "\n" + target;
                    expected.add(fact);
                    expectedByCategory.computeIfAbsent(category, ignored -> new LinkedHashSet<>())
                            .add(fact);
                }
                for (String target : targets(connection, item)) {
                    String fact = caseId + "\n" + target;
                    actual.add(fact);
                    actualByCategory.computeIfAbsent(category, ignored -> new LinkedHashSet<>())
                            .add(fact);
                }
            }
        }

        ResolutionQuality.Metrics overall = ResolutionQuality.evaluate(expected, actual);
        System.out.printf("commons-lang quality: cases=%d precision=%.4f recall=%.4f%n",
                cases.size(), overall.precision(), overall.recall());
        for (String category : expectedByCategory.keySet()) {
            ResolutionQuality.Metrics metrics = ResolutionQuality.evaluate(
                    expectedByCategory.get(category),
                    actualByCategory.getOrDefault(category, Set.of()));
            System.out.printf("  %s precision=%.4f recall=%.4f%n", category,
                    metrics.precision(), metrics.recall());
        }
        assertTrue(overall.precision() >= 1.0,
                () -> "precision=" + overall.precision() + " expected=" + expected + " actual=" + actual);
        assertTrue(overall.recall() >= .95,
                () -> "recall=" + overall.recall() + " expected=" + expected + " actual=" + actual);
    }

    private static List<String> targets(java.sql.Connection connection,
                                        Map<String, Object> item) throws Exception {
        String sql = "SELECT COALESCE(t.target_id,t.external_target_fqn) "
                + "FROM call_sites cs JOIN call_site_owners o ON o.owner_pk=cs.owner_pk "
                + "LEFT JOIN call_site_targets t ON t.call_site_pk=cs.site_pk "
                + "WHERE o.source_file=? AND cs.begin_line=? AND cs.begin_column=? "
                + "ORDER BY COALESCE(t.target_id,t.external_target_fqn)";
        List<String> targets = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, String.valueOf(item.get("file")));
            statement.setInt(2, ((Number) item.get("line")).intValue());
            statement.setInt(3, ((Number) item.get("column")).intValue());
            try (var rows = statement.executeQuery()) {
                while (rows.next() && rows.getString(1) != null) targets.add(rows.getString(1));
            }
        }
        return List.copyOf(targets);
    }

    private static String git(Path cwd, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git"); command.add("-C"); command.add(cwd.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
