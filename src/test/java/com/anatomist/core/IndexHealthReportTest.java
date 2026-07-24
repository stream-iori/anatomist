package com.anatomist.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexHealthReportTest {

    @Test
    void integrityAllowsResolutionGapsButCompleteRejectsThem() {
        IndexHealthReport report = IndexHealthReport.of(List.of(
                diagnostic("info", "THIRDPARTY_SYMBOL_MISSING",
                        "full_extract_call_graph", 8)));

        assertEquals(IndexHealthReport.Status.HEALTHY, report.status());
        assertTrue(report.gate(HealthPolicy.INTEGRITY).passed());
        assertFalse(report.gate(HealthPolicy.COMPLETE).passed());
        Map<?, ?> external = (Map<?, ?>) ((Map<?, ?>) report.dimensions()
                .get("resolution")).get("external");
        assertEquals("partial", external.get("status"));
        assertEquals(8L, external.get("occurrences"));
    }

    @Test
    void integrityRejectsParseAndGraphFailures() {
        IndexHealthReport report = IndexHealthReport.of(List.of(
                diagnostic("warning", "JAVA_PARSE_FAILED", "PARSING", 1),
                diagnostic("warning", "DANGLING_FACTS_DROPPED", "EDGE_BINDING", 2)));

        HealthGateResult gate = report.gate(HealthPolicy.INTEGRITY);
        assertFalse(gate.passed());
        assertEquals(List.of("DANGLING_FACTS_DROPPED", "JAVA_PARSE_FAILED"),
                gate.blockingCodes());
    }

    @Test
    void aggregateDiagnosticIsNotDoubleCountedInResolutionDimensions() {
        IndexHealthReport report = IndexHealthReport.of(List.of(
                diagnostic("info", "UNRESOLVED_SYMBOLS", "RESOLUTION", 100),
                diagnostic("info", "METHOD_NOT_FOUND", "full_extract_call_graph", 7)));

        Map<?, ?> other = (Map<?, ?>) ((Map<?, ?>) report.dimensions()
                .get("resolution")).get("other");
        assertEquals(7L, other.get("occurrences"));
    }

    @Test
    void strictAliasConflictIsRejected() {
        assertEquals(HealthPolicy.COMPLETE, HealthPolicy.resolve(true, null));
        assertThrows(IllegalArgumentException.class,
                () -> HealthPolicy.resolve(true, "integrity"));
    }

    @Test
    void immediateHealthUsesSameBoundedRetentionAsPersistence() {
        List<IndexDiagnostic> diagnostics = new ArrayList<>(IntStream.range(0, 5_100)
                .mapToObj(i -> diagnostic("info", "METHOD_NOT_FOUND",
                        "full_extract_call_graph", 1))
                .toList());
        diagnostics.add(diagnostic("info", "THIRDPARTY_SYMBOL_MISSING",
                "full_extract_call_graph", 1));

        IndexHealthReport report = IndexHealthReport.of(diagnostics);

        assertEquals(IndexDiagnosticRetention.LIMIT, report.diagnostics().size());
        assertTrue(report.diagnostics().stream()
                .anyMatch(d -> "DIAGNOSTIC_STORAGE_TRUNCATED".equals(d.code())
                        && d.count() == 102));
        assertEquals(IndexHealthReport.Status.HEALTHY, report.status());
        assertFalse(report.gate(HealthPolicy.COMPLETE).passed());
        Map<?, ?> external = (Map<?, ?>) ((Map<?, ?>) report.dimensions()
                .get("resolution")).get("external");
        assertEquals("partial", external.get("status"));
        assertEquals(1L, external.get("occurrences"));
    }

    private static IndexDiagnostic diagnostic(String severity, String code,
                                              String phase, long count) {
        return new IndexDiagnostic(
                severity, code, phase, "A.java", ".", "MAIN", null, count, code);
    }
}
