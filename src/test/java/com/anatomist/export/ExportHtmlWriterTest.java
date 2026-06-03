package com.anatomist.export;

import com.anatomist.query.OverviewResult;
import com.anatomist.query.PackageStat;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExportHtmlWriterTest {

    @Test
    void render_substitutesPlaceholderAndEmbedsData() {
        OverviewResult ov = new OverviewResult();
        ov.kindCounts.put("CLASS", 2L);
        ov.packages.add(stat("com.a", 2, 3));
        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("source_package", "com.a");
        dep.put("target_package", "com.b");
        dep.put("relation", "CALLS");
        dep.put("edge_count", 5);
        ov.packageDeps.add(dep);

        Map<String, Object> classDep = new LinkedHashMap<>();
        classDep.put("source", "com.a.Foo");
        classDep.put("target", "com.b.Bar");
        classDep.put("source_package", "com.a");
        classDep.put("target_package", "com.b");
        classDep.put("edge_count", 1);

        String html = ExportHtmlWriter.render(
                ExportHtmlWriter.buildPayload(ov, List.of(classDep)));

        assertFalse(html.contains(ExportHtmlWriter.PLACEHOLDER),
                "placeholder must be replaced");
        assertTrue(html.contains("<html"), "should be a full HTML document");
        assertTrue(html.contains("com.a"), "package name embedded");
        assertTrue(html.contains("com.b.Bar"), "class-level edge target embedded");
        assertTrue(html.contains("\"class_deps\""), "payload key present");
    }

    @Test
    void templateResource_isOnClasspath() {
        assertNotNull(
                ExportHtmlWriter.class.getResourceAsStream(ExportHtmlWriter.TEMPLATE_RESOURCE),
                "template.html must be a bundled resource (check resource-config.json)");
    }

    private static PackageStat stat(String name, long types, long methods) {
        PackageStat p = new PackageStat(name);
        p.types = types; p.methods = methods;
        return p;
    }
}
