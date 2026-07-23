package com.anatomist.query;

import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.anatomist.store.SqliteStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * External call targets have an edge row but intentionally no declaration node.
 * These tests protect their reverse-query entry points from regressing back to
 * node-only resolution.
 */
class ExternalCallTargetQueryTest {

    private static final String EXTERNAL_TYPE = "com.vendor.json.SafeFastjsonParser";
    private static final String PARSE_STRING = EXTERNAL_TYPE + "#parseObject(java.lang.String)";
    private static final String PARSE_TYPED = EXTERNAL_TYPE + "#parseObject(java.lang.String,java.lang.Class)";
    private static final String ENTRY = "com.app.Entry#handle()";
    private static final String ADAPTER = "com.app.JsonAdapter#decode()";
    private static final String OTHER = "com.app.OtherAdapter#decodeTyped()";

    private Path dbPath;

    @BeforeEach
    void buildIndex(@TempDir Path tmp) {
        dbPath = tmp.resolve("external-targets.db");
        try (SqliteStore store = new SqliteStore(dbPath)) {
            store.initSchema();
            ExtractionResult result = new ExtractionResult();
            result.nodes.add(method(ENTRY));
            result.nodes.add(method(ADAPTER));
            result.nodes.add(method(OTHER));
            result.edges.add(internalCall(ENTRY, ADAPTER));
            result.edges.add(Edge.externalCall(ADAPTER, PARSE_STRING, "STATIC", "L12"));
            result.edges.add(Edge.externalCall(OTHER, PARSE_TYPED, "STATIC", "L20"));
            result.edges.add(externalReference(ADAPTER, EXTERNAL_TYPE));
            store.write(result);
        }
    }

    @Test
    void callersOf_externalExactSignatureFindsOnlyThatOverloadAndContinuesUpstream() {
        try (QueryService query = new QueryService(dbPath)) {
            List<EdgeRow> rows = query.callersOf(PARSE_STRING, 2);

            assertTrue(rows.stream().anyMatch(edge -> ADAPTER.equals(edge.source)
                    && PARSE_STRING.equals(edge.externalTargetFqn)
                    && Boolean.TRUE.equals(edge.isExternal)
                    && Boolean.TRUE.equals(edge.externalTarget)
                    && "classpath".equals(edge.resolution)
                    && "EXTRACTED".equals(edge.confidence)
                    && edge.depth == 1), "external direct caller is returned as depth 1");
            assertTrue(rows.stream().anyMatch(edge -> ENTRY.equals(edge.source)
                    && ADAPTER.equals(edge.target) && edge.depth == 2),
                    "reverse traversal continues through project-internal callers");
            assertTrue(rows.stream().noneMatch(edge -> PARSE_TYPED.equals(edge.externalTargetFqn)),
                    "an exact external signature does not include another overload");
        }
    }

    @Test
    void callersOf_externalMethodWithoutSignatureIncludesAllIndexedOverloads() {
        try (QueryService query = new QueryService(dbPath)) {
            List<EdgeRow> rows = query.callersOf(EXTERNAL_TYPE + "#parseObject", 1);

            assertEquals(2, rows.size());
            assertTrue(rows.stream().anyMatch(edge -> PARSE_STRING.equals(edge.externalTargetFqn)));
            assertTrue(rows.stream().anyMatch(edge -> PARSE_TYPED.equals(edge.externalTargetFqn)));
        }
    }

    @Test
    void usedBy_externalTypeFindsCallsAndReferencesWithoutADeclarationNode() {
        try (QueryService query = new QueryService(dbPath)) {
            List<EdgeRow> rows = query.usedBy(EXTERNAL_TYPE);

            assertEquals(3, rows.size());
            assertTrue(rows.stream().allMatch(edge -> Boolean.TRUE.equals(edge.isExternal)));
            assertTrue(rows.stream().anyMatch(edge -> "REFERENCES".equals(edge.relation)
                    && EXTERNAL_TYPE.equals(edge.externalTargetFqn)));
            assertTrue(rows.stream().anyMatch(edge -> "CALLS".equals(edge.relation)
                    && PARSE_STRING.equals(edge.externalTargetFqn)));
        }
    }

    @Test
    void search_exposesExternalTypeAsVirtualAggregationAndHonorsPaging() {
        try (QueryService query = new QueryService(dbPath)) {
            List<NodeRow> rows = query.search("SafeFastjsonParser", null, 20);

            assertEquals(1, rows.size());
            NodeRow external = rows.get(0);
            assertEquals(EXTERNAL_TYPE, external.id);
            assertEquals("EXTERNAL_CLASS", external.kind);
            assertTrue(external.externalTarget);
            assertEquals(3L, external.externalEdgeCount);
            assertEquals(Map.of("CALLS", 2L, "REFERENCES", 1L), external.relationCounts);
            assertEquals(Map.of("classpath", 3L), external.resolutionCounts);
            assertEquals(Map.of("EXTRACTED", 3L), external.confidenceCounts);
            assertEquals(1, query.countSearch("SafeFastjsonParser", null));
            assertEquals(1, query.search("SafeFastjsonParser", "EXTERNAL_CLASS", 20).size());
            assertEquals(1, query.searchByName("SafeFastjsonParser", null, 20).size());
            assertEquals(1, query.countByName("SafeFastjsonParser", null));
            assertTrue(query.search("SafeFastjsonParser", "CLASS", 20).isEmpty());
            assertTrue(query.search("SafeFastjsonParser", null, 20, 1).isEmpty());
        }
    }

    private static Node method(String id) {
        Node node = new Node();
        node.id = id;
        node.label = id.substring(id.indexOf('#') + 1, id.indexOf('('));
        node.kind = "METHOD";
        node.qualifiedName = id.substring(0, id.indexOf('('));
        node.pkg = "com.app";
        node.sourceFile = "src/" + node.label + ".java";
        node.scope = "MAIN";
        return node;
    }

    private static Edge internalCall(String source, String target) {
        return Edge.call(source, target, "INSTANCE", "L5");
    }

    private static Edge externalReference(String source, String target) {
        Edge edge = new Edge();
        edge.sourceId = source;
        edge.externalTargetFqn = target;
        edge.relation = "REFERENCES";
        edge.confidence = "EXTRACTED";
        edge.isExternal = true;
        edge.resolution = "classpath";
        return edge;
    }
}
