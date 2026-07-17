package com.anatomist.query;

import com.anatomist.core.IndexDiagnostic;
import com.anatomist.core.IndexDiagnosticRetention;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.anatomist.store.SqliteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryCoverageServiceTest {

    @Test
    void outgoingCoverageUsesAnchorFileWhileIncomingCoverageIsGlobal(@TempDir Path tmp)
            throws Exception {
        try (SqliteStore store = new SqliteStore(tmp.resolve("index.db"))) {
            store.initSchema();
            ExtractionResult graph = new ExtractionResult();
            graph.nodes.add(node("p.A#run()", "p.A#run()", "A.java"));
            graph.nodes.add(node("p.B#call()", "p.B#call()", "B.java"));
            store.write(graph);
            store.replaceIndexDiagnostics(List.of(
                    diagnostic("THIRDPARTY_SYMBOL_MISSING",
                            "full_extract_call_graph", "/repo/B.java", 3)));

            QueryCoverageService coverage = new QueryCoverageService(store.connection());
            QueryEvidence outgoing = coverage.assess(
                    QueryCoverageService.Capability.CALL_OUTGOING,
                    List.of("p.A#run"), null, "MAIN", false, false);
            assertEquals("confirmed_empty", outgoing.status());
            assertEquals("complete", outgoing.coverage());

            QueryEvidence incoming = coverage.assess(
                    QueryCoverageService.Capability.CALL_INCOMING,
                    List.of("p.A#run"), null, "MAIN", false, false);
            assertEquals("indeterminate", incoming.status());
            assertEquals("QUERY_COVERAGE_INCOMPLETE", incoming.code());
            assertFalse(incoming.negativeConclusionSafe());
        }
    }

    @Test
    void positiveFactRemainsPositiveWhenCoverageIsPartial(@TempDir Path tmp)
            throws Exception {
        try (SqliteStore store = new SqliteStore(tmp.resolve("index.db"))) {
            store.initSchema();
            store.replaceIndexDiagnostics(List.of(
                    diagnostic("CLASSPATH_PARTIAL", "CLASSPATH", null, 1)));

            QueryEvidence evidence = new QueryCoverageService(store.connection()).assess(
                    QueryCoverageService.Capability.CALL_PATH,
                    List.of("A", "B"), null, "MAIN", true, false);

            assertEquals("positive", evidence.status());
            assertEquals("partial", evidence.coverage());
            assertTrue(evidence.diagnosticCounts().containsKey("CLASSPATH_PARTIAL"));
        }
    }

    @Test
    void declarationQueriesIgnoreResolutionOnlyWarnings(@TempDir Path tmp)
            throws Exception {
        try (SqliteStore store = new SqliteStore(tmp.resolve("index.db"))) {
            store.initSchema();
            store.replaceIndexDiagnostics(List.of(
                    diagnostic("THIRDPARTY_SYMBOL_MISSING",
                            "full_extract_reference", "A.java", 9)));

            QueryEvidence evidence = new QueryCoverageService(store.connection()).assess(
                    QueryCoverageService.Capability.DECLARATION,
                    List.of(), null, "MAIN", false, false);

            assertEquals("confirmed_empty", evidence.status());
            assertEquals("complete", evidence.coverage());
        }
    }

    @Test
    void graphIntegrityGapAffectsEveryQueryCapability(@TempDir Path tmp)
            throws Exception {
        try (SqliteStore store = new SqliteStore(tmp.resolve("index.db"))) {
            store.initSchema();
            store.replaceIndexDiagnostics(List.of(
                    diagnostic("DANGLING_FACTS_DROPPED", "EDGE_BINDING", null, 2)));

            QueryEvidence evidence = new QueryCoverageService(store.connection()).assess(
                    QueryCoverageService.Capability.DECLARATION,
                    List.of(), null, "MAIN", false, false);

            assertEquals("indeterminate", evidence.status());
            assertEquals(List.of("graph_integrity"), evidence.affectedDimensions());
        }
    }

    @Test
    void coverageAggregateSurvivesDiagnosticDetailTruncation(@TempDir Path tmp)
            throws Exception {
        try (SqliteStore store = new SqliteStore(tmp.resolve("index.db"))) {
            store.initSchema();
            List<IndexDiagnostic> raw = new ArrayList<>();
            for (int i = 0; i < IndexDiagnosticRetention.LIMIT + 10; i++) {
                raw.add(diagnostic("METHOD_NOT_FOUND", "CALL_GRAPH", "B.java", 1));
            }
            store.replaceIndexDiagnostics(IndexDiagnosticRetention.retain(raw));
            store.replaceAnalysisCoverage(raw);

            QueryEvidence evidence = new QueryCoverageService(store.connection()).assess(
                    QueryCoverageService.Capability.CALL_INCOMING,
                    List.of("p.A#run"), null, "MAIN", false, false);

            assertEquals("indeterminate", evidence.status());
            assertEquals(IndexDiagnosticRetention.LIMIT + 10L,
                    evidence.diagnosticCounts().get("METHOD_NOT_FOUND"));
        }
    }

    private static Node node(String id, String qualifiedName, String sourceFile) {
        Node node = new Node();
        node.id = id;
        node.symbolId = id;
        node.label = "run";
        node.kind = "METHOD";
        node.qualifiedName = qualifiedName;
        node.sourceFile = sourceFile;
        node.module = ".";
        node.scope = "MAIN";
        return node;
    }

    private static IndexDiagnostic diagnostic(String code, String phase,
                                              String sourceFile, long count) {
        return new IndexDiagnostic(
                "warning", code, phase, sourceFile, ".", "MAIN", null, count, code);
    }
}
