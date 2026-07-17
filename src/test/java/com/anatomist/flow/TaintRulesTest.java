package com.anatomist.flow;

import com.anatomist.core.IndexDiagnostic;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaintRulesTest {

    @Test
    void globSupportsExactStarQuestionAndMainNormalization() {
        assertTrue(new TaintRules.Rule("p.A#read", "return").matches("p.A#read"));
        assertTrue(new TaintRules.Rule("p.*#re?d", "return").matches("p.A#read"));
        assertTrue(new TaintRules.Rule("p.**#read", "return").matches("p.A#read"));
        assertTrue(new TaintRules.Rule("p.A#read", "return")
                .matches("p.A::MAIN::#read"));
        assertFalse(new TaintRules.Rule("p.A#read", "return").matches("p.A#reader"));
        assertFalse(new TaintRules.Rule("p.A#re?d", "return").matches("p.A#red"));
    }

    @Test
    void loadKeepsValidRulesAndAggregatesInvalidEntries(@TempDir Path project) throws Exception {
        writeRules(project, """
                {
                  "sources": [
                    {"method":"p.A#source*","slot":"return"},
                    {"method":""},
                    42,
                    {"method":"%s"}
                  ],
                  "sinks": [{"method":"p.A#sink*","slot":"arg:1"}],
                  "sanitizers": ["p.A#clean*"]
                }
                """.formatted("x".repeat(TaintRules.MAX_METHOD_CHARS + 1)));

        TaintRules rules = TaintRules.load(project);
        TaintRules.Match match = rules.classify("p.A#sourceValue");

        assertEquals("return", match.source().slot());
        assertEquals("arg:1", rules.sink("p.A#sinkValue").slot());
        assertEquals("return", rules.sanitizer("p.A#cleanValue").slot());
        List<IndexDiagnostic> skipped = rules.diagnostics().stream()
                .filter(diagnostic -> "TAINT_RULE_SKIPPED".equals(diagnostic.code()))
                .toList();
        assertEquals(2, skipped.size());
        assertEquals(3, skipped.stream().mapToLong(IndexDiagnostic::count).sum());
    }

    @Test
    void loadCapsEachRuleKindAndReportsSkippedCount(@TempDir Path project) throws Exception {
        StringBuilder json = new StringBuilder("{\"sources\":[");
        for (int i = 0; i < TaintRules.MAX_RULES_PER_KIND + 3; i++) {
            if (i > 0) json.append(',');
            json.append('"').append("p.A#m").append(i).append('"');
        }
        json.append("]}");
        writeRules(project, json.toString());

        TaintRules rules = TaintRules.load(project);

        assertNotNull(rules.source("p.A#m" + (TaintRules.MAX_RULES_PER_KIND - 1)));
        assertNull(rules.source("p.A#m" + TaintRules.MAX_RULES_PER_KIND));
        IndexDiagnostic limit = rules.diagnostics().stream()
                .filter(diagnostic -> "TAINT_RULE_LIMIT_EXCEEDED".equals(diagnostic.code()))
                .findFirst().orElseThrow();
        assertEquals(3, limit.count());
        assertEquals("sources", limit.symbol());
    }

    @Test
    void loadRejectsOversizedFileBeforeJsonParsing(@TempDir Path project) throws Exception {
        Path directory = Files.createDirectories(project.resolve(".anatomist"));
        Files.writeString(directory.resolve("taint-rules.json"),
                "{\"padding\":\"" + "x".repeat(TaintRules.MAX_FILE_BYTES) + "\"}");

        TaintRules rules = TaintRules.load(project);

        assertNull(rules.source("*"));
        assertEquals(List.of("TAINT_RULES_TOO_LARGE"),
                rules.diagnostics().stream().map(IndexDiagnostic::code).toList());
    }

    @Test
    void malformedJsonUsesExistingInvalidDiagnostic(@TempDir Path project) throws Exception {
        writeRules(project, "{not-json");

        TaintRules rules = TaintRules.load(project);

        assertEquals(List.of("TAINT_RULES_INVALID"),
                rules.diagnostics().stream().map(IndexDiagnostic::code).toList());
    }

    @Test
    @Tag("regex-performance")
    void adversarialWildcardFailureIsBounded() {
        String pattern = ("*aaaaaaaaab").repeat(32);
        String candidate = "a".repeat(32 * 1024) + "!";
        TaintRules.Rule rule = new TaintRules.Rule(pattern, "return");

        assertTimeout(Duration.ofSeconds(3), () -> assertFalse(rule.matches(candidate)));
    }

    private static void writeRules(Path project, String json) throws Exception {
        Path directory = Files.createDirectories(project.resolve(".anatomist"));
        Files.writeString(directory.resolve("taint-rules.json"), json);
    }
}
