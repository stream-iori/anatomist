package com.anatomist.json;

import com.anatomist.model.SemanticAnnotation;
import com.anatomist.query.ContextResult;
import com.anatomist.query.DocSnippet;
import com.anatomist.query.EdgeRow;
import com.anatomist.query.EnrichResult;
import com.anatomist.query.HierarchyResult;
import com.anatomist.query.NodeRow;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.SemanticAnnotationRow;
import com.anatomist.query.SourceWindow;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Verify every JSON-bearing DTO has a registered codec that round-trips
 *  cleanly and matches the historical Jackson output shape. */
class DtoCodecsTest {

    // Touching these types triggers their static codec registration.
    static {
        DtoCodecs.ensureRegistered();
    }

    @Test
    void nodeRow_snakeCase_skipsNull() {
        NodeRow n = new NodeRow();
        n.id = "com.x.A";
        n.label = "A";
        n.kind = "CLASS";
        n.qualifiedName = "com.x.A";
        n.sourceFile = "A.java";
        // sourceLocation, module = null → must NOT appear (@JsonInclude(NON_NULL))
        String got = Json.writePretty(n);
        assertTrue(got.contains("\"qualified_name\" : \"com.x.A\""), got);
        assertTrue(got.contains("\"source_file\" : \"A.java\""), got);
        assertFalse(got.contains("source_location"), "null fields suppressed: " + got);
        assertFalse(got.contains("\"module\""), "null fields suppressed: " + got);
    }

    @Test
    void edgeRow_isExternalBoolean_snakeCase() {
        EdgeRow e = new EdgeRow();
        e.source = "A#m()";
        e.relation = "CALLS";
        e.isExternal = true;
        e.depth = 1;
        String got = Json.writePretty(e);
        assertTrue(got.contains("\"is_external\" : true"), got);
        assertTrue(got.contains("\"depth\" : 1"), got);
        assertFalse(got.contains("\"target\""), "null target skipped: " + got);
    }

    @Test
    void edgeRow_emitsVia_whenSet_skipsWhenNull() {
        EdgeRow e = new EdgeRow();
        e.source = "A#m()";
        e.target = "Dep#run()";
        e.relation = "CALLS";
        e.isExternal = false;
        String withoutVia = Json.writePretty(e);
        assertFalse(withoutVia.contains("\"via\""), "via skipped when null: " + withoutVia);

        e.via = "A#m()$anon@L1#process()";
        String withVia = Json.writePretty(e);
        assertTrue(withVia.contains("\"via\" : \"A#m()$anon@L1#process()\""), withVia);
    }

    @Test
    void edgeRow_emitsSourceWindow_whenSet() {
        EdgeRow e = new EdgeRow();
        e.source = "A#m()";
        e.relation = "CALLS";
        SourceWindow w = new SourceWindow();
        w.path = "A.java";
        w.line = 12;
        w.startLine = 11;
        w.endLine = 13;
        w.snippet = "12 | dep.run();";
        e.sourceWindow = w;

        String got = Json.writePretty(e);

        assertTrue(got.contains("\"source_window\""), got);
        assertTrue(got.contains("\"start_line\" : 11"), got);
        assertTrue(got.contains("\"snippet\" : \"12 | dep.run();\""), got);
    }

    @Test
    void hierarchyEntry_emitsNullFields() {
        // HierarchyResult.Entry has NO @JsonInclude(NON_NULL) — nulls MUST appear.
        HierarchyResult.Entry e = new HierarchyResult.Entry();
        e.id = "com.x.A"; e.label = "A"; e.qualifiedName = "com.x.A";
        e.role = "self"; e.depth = 0;
        // isExternal / externalTargetFqn = null
        String got = Json.writePretty(e);
        assertTrue(got.contains("\"is_external\" : null"), got);
        assertTrue(got.contains("\"external_target_fqn\" : null"), got);
    }

    @Test
    void hierarchyResult_listsAreArrays() {
        HierarchyResult h = new HierarchyResult();
        HierarchyResult.Entry e = new HierarchyResult.Entry();
        e.role = "self"; e.depth = 0;
        h.extendsChain.add(e);
        String got = Json.writePretty(h);
        assertTrue(got.contains("\"extends_chain\""), got);
        assertTrue(got.contains("\"implements_list\" : [ ]"), got);
    }

    @Test
    void docSnippet_skipsNull() {
        DocSnippet d = new DocSnippet();
        d.title = "T"; d.path = "p"; d.docType = "ADR";
        // snippet = null
        String got = Json.writePretty(d);
        assertTrue(got.contains("\"doc_type\" : \"ADR\""), got);
        assertFalse(got.contains("snippet"), got);
    }

    @Test
    void semanticAnnotationRow_snakeCase() {
        SemanticAnnotationRow r = new SemanticAnnotationRow();
        r.category = "REVIEWED";
        r.businessLabel = "reviewed";
        r.source = "LLM";
        r.confidence = "MEDIUM";
        String got = Json.writePretty(r);
        assertTrue(got.contains("\"business_label\" : \"reviewed\""), got);
        assertTrue(got.contains("\"category\" : \"REVIEWED\""), got);
        assertFalse(got.contains("business_description"));
        assertFalse(got.contains("domain_context"));
    }

    @Test
    void contextResult_nestedDtos() {
        ContextResult r = new ContextResult();
        NodeRow n = new NodeRow();
        n.id = "com.x.A"; n.label = "A"; n.kind = "CLASS"; n.qualifiedName = "com.x.A";
        n.sourceFile = "A.java";
        r.node = n;
        String got = Json.writePretty(r);
        assertTrue(got.contains("\"node\""), got);
        assertTrue(got.contains("\"members\" : [ ]"), got);
        assertTrue(got.contains("\"annotations\" : [ ]"), got);
        // callees is null → suppressed (NON_NULL)
        assertFalse(got.contains("callees"), got);
    }

    @Test
    void queryEnvelope_statsMapInlined() {
        NodeRow n = new NodeRow();
        n.id = "x"; n.label = "x"; n.kind = "CLASS"; n.qualifiedName = "x"; n.sourceFile = "X.java";
        QueryEnvelope env = new QueryEnvelope("search foo", List.of(n));
        String got = Json.writePretty(env);
        assertTrue(got.startsWith("{"), got);
        assertTrue(got.contains("\"query\" : \"search foo\""), got);
        assertTrue(got.contains("\"results\""), got);
        assertTrue(got.contains("\"stats\""), got);
        assertTrue(got.contains("\"total\" : 1"), got);
    }

    @Test
    void enrichResult_packageMode() {
        EnrichResult r = new EnrichResult();
        r.pkg = "com.x";
        // node = null → suppressed (NON_NULL)
        String got = Json.writePretty(r);
        assertTrue(got.contains("\"pkg\" : \"com.x\""), got);
        assertFalse(got.contains("\"node\""), got);
    }

    @Test
    void semanticAnnotation_readSnakeCase() {
        String src = "{\"node_id\":\"com.x.A\",\"category\":\"REVIEWED\","
                + "\"business_label\":\"label\",\"source\":\"LLM\",\"confidence\":\"HIGH\"}";
        SemanticAnnotation sa = SemanticAnnotation.fromJson((Map<String, Object>) Json.parseTree(src));
        assertEquals("com.x.A", sa.nodeId);
        assertEquals("REVIEWED", sa.category);
        assertEquals("label", sa.businessLabel);
        assertEquals("LLM", sa.source);
        assertEquals("HIGH", sa.confidence);
    }

    @Test
    void semanticAnnotation_listReadFromArray() {
        String src = "[{\"node_id\":\"a\",\"category\":\"c\",\"source\":\"LLM\"},"
                + "{\"node_id\":\"b\",\"category\":\"c\",\"source\":\"DOC\"}]";
        List<SemanticAnnotation> list = SemanticAnnotation.listFromJson(src);
        assertEquals(2, list.size());
        assertEquals("a", list.get(0).nodeId);
        assertEquals("DOC", list.get(1).source);
    }

    @Test
    void mapValue_passesThroughGenericTree() {
        // QueryService stuffs parsed Map into row.put("attributes", tree).
        // Generic Map writing must respect snake_case-of-keys-as-given (no transform).
        Map<String, Object> row = new LinkedHashMap<>();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("value", "/api/orders");
        row.put("annotation_fqn", "org.springframework.web.bind.annotation.RequestMapping");
        row.put("attributes", attrs);
        String got = Json.writePretty(row);
        assertTrue(got.contains("\"annotation_fqn\""), got);
        assertTrue(got.contains("\"attributes\" : {"), got);
        assertTrue(got.contains("\"value\" : \"/api/orders\""), got);
    }
}
