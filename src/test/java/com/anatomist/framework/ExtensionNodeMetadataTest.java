package com.anatomist.framework;

import com.anatomist.json.Json;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionNodeMetadataTest {

    @Test
    void namespacesMergeIdempotentlyAndSurviveClone() {
        var unit = StaticJavaParser.parse("class Sample { String value; }");
        var type = unit.getClassByName("Sample").orElseThrow();
        ExtensionNodeMetadata.put(type, "lombok", Map.of("coverage", "complete"));
        ExtensionNodeMetadata.put(type, "lombok", Map.of("coverage", "complete"));
        ExtensionNodeMetadata.put(type, "other", Map.of("enabled", true));

        var cloned = unit.clone().getClassByName("Sample").orElseThrow();
        com.anatomist.model.Node fact = new com.anatomist.model.Node();
        fact.metadata = "{\"isAbstract\":false}";
        ExtensionNodeMetadata.apply(cloned, fact);

        Object parsed = Json.parseTree(fact.metadata);
        assertInstanceOf(Map.class, parsed);
        Map<?, ?> metadata = (Map<?, ?>) parsed;
        assertEquals(false, metadata.get("isAbstract"));
        assertEquals(Map.of("coverage", "complete"), metadata.get("lombok"));
        assertEquals(Map.of("enabled", true), metadata.get("other"));
    }

    @Test
    void conflictingNamespaceFailsFast() {
        var type = StaticJavaParser.parse("class Sample {}").getClassByName("Sample").orElseThrow();
        ExtensionNodeMetadata.put(type, "lombok", Map.of("coverage", "complete"));

        var failure = assertThrows(IllegalStateException.class, () ->
                ExtensionNodeMetadata.put(type, "lombok", Map.of("coverage", "partial")));

        assertTrue(failure.getMessage().contains("EXTENSION_METADATA_NAMESPACE_CONFLICT"));
    }
}
