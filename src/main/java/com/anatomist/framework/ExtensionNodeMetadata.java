package com.anatomist.framework;

import com.anatomist.json.Json;
import com.github.javaparser.ast.DataKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Namespaced metadata contributed by an AST model extension to a source declaration. */
public final class ExtensionNodeMetadata {
    private static final ExtensionMetadataKey KEY = new ExtensionMetadataKey();

    private ExtensionNodeMetadata() {}

    public static void put(com.github.javaparser.ast.Node node,
                           String namespace,
                           Map<String, ?> value) {
        if (node == null || value == null) return;
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("extension metadata namespace must not be blank");
        }
        Object normalized = Json.parseTree(Json.writeCompact(value));
        if (!(normalized instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("extension metadata value must be an object");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        map.forEach((key, item) -> payload.put(String.valueOf(key), item));

        Map<String, Map<String, Object>> values = new TreeMap<>(
                node.findData(KEY).orElse(Map.of()));
        Map<String, Object> prior = values.get(namespace);
        if (prior != null && !prior.equals(payload)) {
            throw new IllegalStateException("EXTENSION_METADATA_NAMESPACE_CONFLICT: " + namespace);
        }
        values.put(namespace, Collections.unmodifiableMap(payload));
        node.setData(KEY, Collections.unmodifiableMap(new LinkedHashMap<>(values)));
    }

    public static Map<String, Map<String, Object>> of(com.github.javaparser.ast.Node node) {
        return node == null ? Map.of() : node.findData(KEY).orElse(Map.of());
    }

    public static void apply(com.github.javaparser.ast.Node ast,
                             com.anatomist.model.Node fact) {
        if (ast == null || fact == null) return;
        Map<String, Map<String, Object>> extensions = of(ast);
        if (extensions.isEmpty()) return;

        Map<String, Object> metadata = parseObject(fact.metadata);
        for (Map.Entry<String, Map<String, Object>> extension : extensions.entrySet()) {
            Object prior = metadata.get(extension.getKey());
            if (prior != null && !prior.equals(extension.getValue())) {
                throw new IllegalStateException(
                        "EXTENSION_METADATA_NAMESPACE_CONFLICT: " + extension.getKey());
            }
            metadata.put(extension.getKey(), extension.getValue());
        }
        fact.metadata = Json.writeCompact(metadata);
    }

    private static Map<String, Object> parseObject(String json) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        Object parsed = Json.parseTree(json);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalStateException("node metadata must be a JSON object");
        }
        map.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    private static final class ExtensionMetadataKey
            extends DataKey<Map<String, Map<String, Object>>> {}
}
