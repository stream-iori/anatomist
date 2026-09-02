package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.test.CliTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextSourceIT {
    private static final String SOURCE_FILE = "src/main/java/p/Sample.java";

    @TempDir Path tmp;
    private Path project;
    private Path source;
    private Path db;

    @BeforeEach
    void indexProject() throws Exception {
        project = tmp.resolve("project");
        source = project.resolve(SOURCE_FILE);
        Files.createDirectories(source.getParent());
        Files.writeString(project.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>p</groupId><artifactId>sample</artifactId><version>1</version>
                </project>
                """, StandardCharsets.UTF_8);
        Files.writeString(source, """
                package p;

                public class Sample {
                    private int value;

                    public Sample() {}

                    @Deprecated
                    public int calculate(int input) {
                        return input + 1;
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(source.resolveSibling("Declarations.java"), """
                package p;

                interface Contract { void run(); }
                enum Mode { ON, OFF }
                @interface Marker {}
                record Point(int x) {
                    Point {
                        if (x < 0) throw new IllegalArgumentException();
                    }
                }
                """, StandardCharsets.UTF_8);
        db = tmp.resolve("index.db");
        CliTestSupport.assertIndexOk(project, "--no-classpath", "--java-version", "21",
                "--output", db.toString(), "--format", "json");
    }

    @Test
    void exactMethodSourceIsRangeBoundedAndPageable() throws Exception {
        CliTestSupport.RunResult first = context("p.Sample#calculate(int)", "--source",
                "--source-limit", "2", "--index", db.toString());

        assertEquals(0, first.exitCode(), first.stderr());
        Map<?, ?> envelope = object(first.stdout());
        Map<?, ?> sourceView = sourceView(envelope);
        assertEquals("ok", sourceView.get("status"));
        assertEquals("L8:C5-L11:C5", sourceView.get("source_range"));
        assertEquals(0, number(sourceView, "offset"));
        assertEquals(2, number(sourceView, "limit"));
        assertEquals(4, number(sourceView, "total_lines"));
        assertEquals(Boolean.TRUE, sourceView.get("truncated"));
        assertTrue(String.valueOf(sourceView.get("snippet")).contains("8 | @Deprecated"));
        assertFalse(String.valueOf(sourceView.get("snippet")).contains("package p"));
        String next = String.valueOf(((List<?>) envelope.get("next_queries")).getFirst());
        assertTrue(next.contains("--source-limit 2"), next);
        assertTrue(next.contains("--source-offset 2"), next);

        CliTestSupport.RunResult second = context("p.Sample#calculate(int)", "--source",
                "--source-limit", "2", "--source-offset", "2", "--index", db.toString());
        Map<?, ?> secondSource = sourceView(object(second.stdout()));
        assertEquals(2, number(secondSource, "offset"));
        assertEquals(2, number(secondSource, "limit"));
        assertEquals(Boolean.FALSE, secondSource.get("truncated"));
        assertTrue(String.valueOf(secondSource.get("snippet")).contains("return input + 1"));

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.prepareStatement("SELECT begin_line,begin_column,end_line,end_column "
                     + "FROM declarations WHERE symbol_id='p.Sample#calculate(int)'")) {
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(8, rows.getInt(1));
                assertEquals(5, rows.getInt(2));
                assertEquals(11, rows.getInt(3));
                assertEquals(5, rows.getInt(4));
            }
        }
    }

    @Test
    void typeAndConstructorDeclarationsAreSupported() throws Exception {
        CliTestSupport.RunResult type = context("p.Sample", "--source", "--index", db.toString());
        Map<?, ?> typeSource = sourceView(object(type.stdout()));
        assertEquals("L3:C1-L12:C1", typeSource.get("source_range"));
        assertTrue(String.valueOf(typeSource.get("snippet")).startsWith(" 3 | public class Sample"));
        assertFalse(String.valueOf(typeSource.get("snippet")).contains("package p"));

        CliTestSupport.RunResult constructor = context("p.Sample#Sample()", "--source",
                "--index", db.toString());
        assertEquals(0, constructor.exitCode(), constructor.stderr());
        assertTrue(String.valueOf(sourceView(object(constructor.stdout())).get("snippet"))
                .contains("public Sample() {}"));
    }

    @Test
    void allTypeFormsAndCompactConstructorAreSourceBacked() throws Exception {
        assertSnippetContains("p.Contract", "interface Contract");
        assertSnippetContains("p.Mode", "enum Mode");
        assertSnippetContains("p.Marker", "@interface Marker");
        assertSnippetContains("p.Point", "record Point");
        assertSnippetContains("p.Point#Point(int)", "Point {");
    }

    @Test
    void changedSourceReturnsSoftStaleWithoutSnippet() throws Exception {
        Files.writeString(source, Files.readString(source) + "// changed\n", StandardCharsets.UTF_8);

        CliTestSupport.RunResult result = context("p.Sample#calculate(int)", "--source",
                "--index", db.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        Map<?, ?> sourceView = sourceView(object(result.stdout()));
        assertEquals("stale", sourceView.get("status"));
        assertEquals("INDEX_STALE", sourceView.get("warning_code"));
        assertFalse(sourceView.containsKey("snippet"), sourceView.toString());
    }

    @Test
    void incrementalReplacementRefreshesSourceRange() throws Exception {
        Files.writeString(source, Files.readString(source).replace(
                "    @Deprecated", "\n\n    @Deprecated"), StandardCharsets.UTF_8);
        CliTestSupport.assertIndexOk(project, "--incremental", "--no-classpath", "--java-version", "21",
                "--output", db.toString(), "--format", "json");

        CliTestSupport.RunResult result = context("p.Sample#calculate(int)", "--source",
                "--index", db.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        Map<?, ?> sourceView = sourceView(object(result.stdout()));
        assertEquals("ok", sourceView.get("status"));
        assertEquals("L10:C5-L13:C5", sourceView.get("source_range"));
        assertTrue(String.valueOf(sourceView.get("snippet")).contains("10 | @Deprecated"));
    }

    @Test
    void unsupportedNodeFailsWithStructuredUnavailableSource() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.prepareStatement("UPDATE declarations SET begin_line=NULL, "
                     + "begin_column=NULL, end_line=NULL, end_column=NULL "
                     + "WHERE symbol_id='p.Sample#calculate(int)'")) {
            assertEquals(1, statement.executeUpdate());
        }
        CliTestSupport.RunResult result = context("p.Sample#calculate(int)", "--source",
                "--index", db.toString());

        assertEquals(3, result.exitCode(), result.stderr());
        Map<?, ?> envelope = object(result.stdout());
        Map<?, ?> sourceView = sourceView(envelope);
        assertEquals("unavailable", sourceView.get("status"));
        assertEquals("SOURCE_RANGE_UNAVAILABLE", sourceView.get("warning_code"));
        assertEquals("indeterminate", ((Map<?, ?>) envelope.get("evidence")).get("status"));
    }

    @Test
    void syntheticDeclarationIsUnavailableEvenWhenItHasARange() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.prepareStatement("UPDATE declarations SET synthetic=1 "
                     + "WHERE symbol_id='p.Sample#calculate(int)'")) {
            assertEquals(1, statement.executeUpdate());
        }

        CliTestSupport.RunResult result = context("p.Sample#calculate(int)", "--source",
                "--index", db.toString());

        assertSourceFailure(result, "unavailable", "SOURCE_RANGE_UNAVAILABLE");
    }

    @Test
    void missingAndMalformedSourceLayoutsFailClosed() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "DELETE FROM project_meta WHERE key='source_layout'"));
        }
        assertSourceFailure(context("p.Sample#calculate(int)", "--source",
                        "--index", db.toString()),
                "error", "SOURCE_PROFILE_INCOMPLETE");

        CliTestSupport.assertIndexOk(project, "--no-classpath", "--java-version", "21",
                "--output", db.toString(), "--format", "json");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.prepareStatement(
                     "UPDATE project_meta SET value=? WHERE key='source_layout'")) {
            statement.setString(1, ".@MAIN=" + source.getParent() + "\nbroken");
            assertEquals(1, statement.executeUpdate());
        }
        assertSourceFailure(context("p.Sample#calculate(int)", "--source",
                        "--index", db.toString()),
                "error", "SOURCE_PROFILE_INCOMPLETE");
    }

    @Test
    void sourceRootSymlinkEscapeIsRejected() throws Exception {
        Path outside = tmp.resolve("outside.java");
        Files.writeString(outside, Files.readString(source), StandardCharsets.UTF_8);
        Path link = source.resolveSibling("Sample.link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException failure) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "symbolic links are unavailable: " + failure.getMessage());
        }
        Files.delete(source);
        Files.move(link, source);

        CliTestSupport.RunResult result = context("p.Sample#calculate(int)", "--source",
                "--index", db.toString());

        assertSourceFailure(result, "error", "SOURCE_PATH_INVALID");
    }

    @Test
    void indexedPathTraversalIsRejected() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            assertTrue(statement.executeUpdate("UPDATE nodes SET source_file='../outside.java' "
                    + "WHERE symbol_id='p.Sample#calculate(int)'") >= 1);
            assertEquals(1, statement.executeUpdate("UPDATE declarations SET source_file='../outside.java' "
                    + "WHERE symbol_id='p.Sample#calculate(int)'"));
            assertEquals(1, statement.executeUpdate("UPDATE file_cache SET source_file='../outside.java' "
                    + "WHERE source_file='" + SOURCE_FILE + "'"));
        }

        CliTestSupport.RunResult result = context("p.Sample#calculate(int)", "--source",
                "--index", db.toString());

        assertEquals(3, result.exitCode(), result.stderr());
        Map<?, ?> sourceView = sourceView(object(result.stdout()));
        assertEquals("error", sourceView.get("status"));
        assertEquals("SOURCE_PATH_INVALID", sourceView.get("warning_code"));
    }

    @Test
    void markdownAndOptionValidationFollowSourceContract() throws Exception {
        CliTestSupport.RunResult markdown = context("p.Sample#calculate(int)", "--source",
                "--source-limit", "2", "--format", "markdown", "--index", db.toString());
        assertEquals(0, markdown.exitCode(), markdown.stderr());
        assertTrue(markdown.stdout().contains("## Source"));
        assertTrue(markdown.stdout().contains("```java"));
        assertTrue(markdown.stdout().contains("## Next queries"));

        assertEquals(2, context("p.Sample", "--source-limit", "2", "--index", db.toString()).exitCode());
        assertEquals(2, context("p.Sample", "--source", "--enrich", "--index", db.toString()).exitCode());
        assertEquals(2, context("p.Sample", "--source", "--source-limit", "1001",
                "--index", db.toString()).exitCode());
        assertEquals(2, context("p.Sample", "--source", "--source-offset", "-1",
                "--index", db.toString()).exitCode());
    }

    @Test
    void defaultLimitAndPastEndOffsetAreStable() throws Exception {
        Path longSource = source.resolveSibling("LongSample.java");
        StringBuilder java = new StringBuilder("package p;\nclass LongSample {\n  void longMethod() {\n");
        for (int i = 0; i < 205; i++) java.append("    int value").append(i).append(" = ").append(i).append(";\n");
        java.append("  }\n}\n");
        Files.writeString(longSource, java, StandardCharsets.UTF_8);
        CliTestSupport.assertIndexOk(project, "--incremental", "--no-classpath", "--java-version", "21",
                "--output", db.toString(), "--format", "json");

        Map<?, ?> firstEnvelope = object(context("p.LongSample#longMethod()", "--source",
                "--index", db.toString()).stdout());
        Map<?, ?> first = sourceView(firstEnvelope);
        assertEquals(200, number(first, "limit"));
        assertEquals(Boolean.TRUE, first.get("truncated"));
        assertTrue(String.valueOf(((List<?>) firstEnvelope.get("next_queries")).getFirst())
                .contains("--source-limit 200"));

        Map<?, ?> exhaustedEnvelope = object(context("p.LongSample#longMethod()", "--source",
                "--source-offset", "9999", "--index", db.toString()).stdout());
        Map<?, ?> exhausted = sourceView(exhaustedEnvelope);
        assertEquals(number(exhausted, "total_lines"), number(exhausted, "offset"));
        assertEquals("", exhausted.get("snippet"));
        assertFalse(exhaustedEnvelope.containsKey("next_queries"));
    }

    @Test
    void combinedPagingPreservesIndependentContinuationsAndCallees() throws Exception {
        Map<?, ?> envelope = object(context("p.Sample", "--source", "--source-limit", "2",
                "--members-limit", "1", "--with-callees=1", "--index", db.toString()).stdout());

        List<?> next = (List<?>) envelope.get("next_queries");
        assertEquals(2, next.size(), next.toString());
        assertTrue(next.stream().map(String::valueOf).anyMatch(q -> q.contains("--members-offset 1")));
        assertTrue(next.stream().map(String::valueOf).anyMatch(q -> q.contains("--source-offset 2")));
        assertTrue(next.stream().map(String::valueOf).allMatch(q -> q.contains("--with-callees=1")));
        assertTrue(next.stream().map(String::valueOf).allMatch(q -> q.contains("--source-limit 2")));
    }

    private static CliTestSupport.RunResult context(String... args) throws Exception {
        return CliTestSupport.capture(() -> new CommandLine(new ContextCommand()).execute(args));
    }

    private void assertSnippetContains(String selector, String expected) throws Exception {
        CliTestSupport.RunResult result = context(selector, "--source", "--index", db.toString());
        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(String.valueOf(sourceView(object(result.stdout())).get("snippet")).contains(expected),
                result.stdout());
    }

    private static void assertSourceFailure(CliTestSupport.RunResult result, String status, String code) {
        assertEquals(3, result.exitCode(), result.stderr());
        Map<?, ?> envelope = object(result.stdout());
        Map<?, ?> source = sourceView(envelope);
        assertEquals(status, source.get("status"));
        assertEquals(code, source.get("warning_code"));
        assertFalse(source.containsKey("snippet"), source.toString());
        assertEquals("indeterminate", ((Map<?, ?>) envelope.get("evidence")).get("status"));
    }

    private static Map<?, ?> object(String json) {
        Object tree = Json.parseTree(json);
        assertInstanceOf(Map.class, tree, json);
        return (Map<?, ?>) tree;
    }

    private static Map<?, ?> sourceView(Map<?, ?> envelope) {
        Map<?, ?> result = (Map<?, ?>) ((List<?>) envelope.get("results")).getFirst();
        return (Map<?, ?>) result.get("source");
    }

    private static int number(Map<?, ?> values, String key) {
        return ((Number) values.get(key)).intValue();
    }
}
