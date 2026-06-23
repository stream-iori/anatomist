package com.anatomist.export;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArchExportTest {

    @Test
    void renderArch_substitutesPlaceholder() {
        Map<String, Object> payload = minimalPayload();
        String html = ExportHtmlWriter.renderArch(payload);

        assertFalse(html.contains(ExportHtmlWriter.PLACEHOLDER),
                "placeholder must be replaced");
        assertTrue(html.contains("<html"), "should be a full HTML document");
        assertTrue(html.contains("test-project"), "project name embedded");
        assertTrue(html.contains("TestClass"), "type label embedded");
    }

    @Test
    void renderArch_embedsCodeSnippets() {
        Map<String, Object> payload = minimalPayload();
        Map<String, String> snippets = new LinkedHashMap<>();
        snippets.put("com.test.TestClass#doWork()", "public void doWork() {\n    System.out.println(\"hi\");\n}");
        payload.put("code_snippets", snippets);

        String html = ExportHtmlWriter.renderArch(payload);

        assertTrue(html.contains("doWork"), "code snippet embedded");
        assertTrue(html.contains("System.out.println"), "method body embedded");
    }

    @Test
    void renderArch_handlesEmptyPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project_name", "empty");
        payload.put("stats", emptyStats());
        payload.put("types", List.of());
        payload.put("members", new LinkedHashMap<>());
        payload.put("type_edges", List.of());
        payload.put("code_snippets", new LinkedHashMap<>());

        String html = ExportHtmlWriter.renderArch(payload);

        assertFalse(html.contains(ExportHtmlWriter.PLACEHOLDER));
        assertTrue(html.contains("<html"));
    }

    @Test
    void archTemplateResource_isOnClasspath() {
        assertNotNull(
                ExportHtmlWriter.class.getResourceAsStream(ExportHtmlWriter.ARCH_TEMPLATE_RESOURCE),
                "arch-template.html must be a bundled resource");
    }

    private static Map<String, Object> minimalPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project_name", "test-project");
        payload.put("stats", emptyStats());

        List<Map<String, Object>> types = new ArrayList<>();
        Map<String, Object> type = new LinkedHashMap<>();
        type.put("id", "com.test.TestClass");
        type.put("label", "TestClass");
        type.put("kind", "CLASS");
        type.put("qualified_name", "com.test.TestClass");
        type.put("package", "com.test");
        type.put("is_abstract", false);
        type.put("annotations", List.of("Service"));
        type.put("method_count", 3);
        type.put("field_count", 1);
        type.put("javadoc", null);
        types.add(type);
        payload.put("types", types);

        Map<String, List<Map<String, Object>>> members = new LinkedHashMap<>();
        List<Map<String, Object>> memberList = new ArrayList<>();
        Map<String, Object> method = new LinkedHashMap<>();
        method.put("id", "com.test.TestClass#doWork()");
        method.put("label", "doWork");
        method.put("kind", "METHOD");
        method.put("signature", "doWork()");
        method.put("return_type", "void");
        method.put("modifiers", List.of("public"));
        method.put("annotations", List.of());
        method.put("source_location", "L10");
        method.put("javadoc", null);
        memberList.add(method);
        members.put("com.test.TestClass", memberList);
        payload.put("members", members);

        payload.put("type_edges", List.of());
        payload.put("code_snippets", new LinkedHashMap<>());

        return payload;
    }

    private static Map<String, Object> emptyStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("kind_counts", new LinkedHashMap<>());
        stats.put("internal_edge_counts", new LinkedHashMap<>());
        stats.put("external_edge_counts", new LinkedHashMap<>());
        stats.put("package_count", 0L);
        return stats;
    }
}
