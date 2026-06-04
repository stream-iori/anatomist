package com.anatomist.cli;

import com.anatomist.json.Json;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Verify that picocli-codegen ran at compile time and produced the
 *  native-image config resources for every CLI subcommand. */
class PicocliCodegenIT {

    /** picocli-codegen writes to META-INF/native-image/picocli-generated/${project}/...
     *  We set {@code -Aproject=anatomist} in pom.xml. */
    private static final String REFLECT =
            "META-INF/native-image/picocli-generated/anatomist/reflect-config.json";
    private static final String RESOURCE =
            "META-INF/native-image/picocli-generated/anatomist/resource-config.json";

    @Test
    void reflectConfig_isOnClasspath() {
        assertNotNull(getClass().getClassLoader().getResourceAsStream(REFLECT),
                "picocli-codegen reflect-config missing — annotation processor not configured");
    }

    @Test
    void resourceConfig_isOnClasspath() {
        assertNotNull(getClass().getClassLoader().getResourceAsStream(RESOURCE),
                "picocli-codegen resource-config missing — annotation processor not configured");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reflectConfig_listsAllSubcommands() throws Exception {
        String json;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(REFLECT)) {
            assertNotNull(in);
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<Object> entries = (List<Object>) Json.parseTree(json);
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Object e : entries) {
            Map<String, Object> m = (Map<String, Object>) e;
            String name = (String) m.get("name");
            if (name != null) names.add(name);
        }
        // Spot-check: the picocli command classes themselves must be reflected.
        assertTrue(names.contains("com.anatomist.cli.AnatomistCli"),
                "AnatomistCli not in reflect-config: " + names);
        assertTrue(names.contains("com.anatomist.cli.IndexCommand"));
        assertTrue(names.contains("com.anatomist.cli.FieldAccessCommand"));
        assertTrue(names.contains("com.anatomist.cli.AnnotateCommand"));
    }

    @Test
    void schemaSql_isDeclaredAsNativeImageResource() throws Exception {
        // Our own resource-config.json under META-INF/native-image/com.antcodes/anatomist/
        // must list schema.sql so SqliteStore.initSchema() works in a native binary.
        String path = "META-INF/native-image/com.antcodes/anatomist/resource-config.json";
        String json;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "anatomist resource-config.json missing");
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(json.contains("schema.sql"),
                "resource-config does not declare schema.sql: " + json);
    }
}
