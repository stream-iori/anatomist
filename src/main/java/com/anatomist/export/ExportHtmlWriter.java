package com.anatomist.export;

import com.anatomist.json.Json;
import com.anatomist.query.DtoCodecs;
import com.anatomist.query.ClassEdge;
import com.anatomist.query.OverviewResult;
import com.anatomist.query.PackageStat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders a single self-contained HTML file from index data. The HTML template
 *  ships as a classpath resource and carries a {@code /*__ANATOMIST_DATA__*}{@code /}
 *  placeholder that we replace with a compact JSON data blob. No reflection,
 *  no network, no template engine — native-image safe. */
public final class ExportHtmlWriter {

    static final String TEMPLATE_RESOURCE = "/export/template.html";
    static final String ARCH_TEMPLATE_RESOURCE = "/export/arch-template.html";
    static final String PLACEHOLDER = "/*__ANATOMIST_DATA__*/";

    private ExportHtmlWriter() {}

    /** Build the JSON payload embedded into the HTML page. */
    public static Map<String, Object> buildPayload(OverviewResult ov,
                                                    List<ClassEdge> classDeps) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind_counts", ov.kindCounts);
        payload.put("internal_edge_counts", ov.internalEdgeCounts);
        payload.put("external_edge_counts", ov.externalEdgeCounts);
        payload.put("packages", packageList(ov.packages));
        payload.put("package_deps", ov.packageDeps);
        payload.put("class_deps", classDeps);
        return payload;
    }

    private static List<Map<String, Object>> packageList(List<PackageStat> packages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PackageStat p : packages) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name);
            m.put("types", p.types);
            m.put("methods", p.methods);
            out.add(m);
        }
        return out;
    }

    /** Render the full HTML document as a string. */
    public static String render(Map<String, Object> payload) {
        DtoCodecs.ensureRegistered(); // export path bypasses JsonFormatter; register ClassEdge & friends
        String template = readTemplate();
        String dataBlob = Json.writeCompact(payload);
        int idx = template.indexOf(PLACEHOLDER);
        if (idx < 0) {
            throw new IllegalStateException(
                    "HTML template is missing the " + PLACEHOLDER + " placeholder");
        }
        // Use replace on the exact token (single occurrence expected).
        return template.replace(PLACEHOLDER, dataBlob);
    }

    /** Render the architecture visualization HTML from a pre-built payload. */
    public static String renderArch(Map<String, Object> payload) {
        DtoCodecs.ensureRegistered();
        String template = readTemplate(ARCH_TEMPLATE_RESOURCE);
        String dataBlob = Json.writeCompact(payload);
        int idx = template.indexOf(PLACEHOLDER);
        if (idx < 0) {
            throw new IllegalStateException(
                    "Arch HTML template is missing the " + PLACEHOLDER + " placeholder");
        }
        return template.replace(PLACEHOLDER, dataBlob);
    }

    private static String readTemplate() {
        return readTemplate(TEMPLATE_RESOURCE);
    }

    private static String readTemplate(String resource) {
        InputStream in = ExportHtmlWriter.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("Missing classpath resource: " + resource);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
