package com.anatomist.framework;

import com.anatomist.json.Json;
import com.github.javaparser.ast.DataKey;
import com.github.javaparser.ast.Node;

import java.util.LinkedHashMap;
import java.util.Map;

/** Provenance for declarations synthesized into an in-memory AST. */
public record SyntheticOrigin(
        String producerId,
        String generator,
        String generatorMode,
        String generatedFrom,
        String confidence,
        boolean bodyAvailable
) {
    public static final SyntheticOriginKey KEY = new SyntheticOriginKey();

    public static void mark(Node node, SyntheticOrigin origin) {
        if (node != null && origin != null) node.setData(KEY, origin);
    }

    public static SyntheticOrigin of(Node node) {
        return node == null ? null : node.findData(KEY).orElse(null);
    }

    public static void apply(Node ast, com.anatomist.model.Node fact) {
        SyntheticOrigin origin = of(ast);
        if (origin == null || fact == null) return;
        fact.producerId = origin.producerId();
        fact.metadata = origin.mergeMetadata(fact.metadata);
    }

    public static void apply(Node ast, com.anatomist.model.Declaration fact) {
        SyntheticOrigin origin = of(ast);
        if (origin == null || fact == null) return;
        fact.synthetic = true;
        fact.producerId = origin.producerId();
    }

    @SuppressWarnings("unchecked")
    public String mergeMetadata(String existing) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (existing != null && !existing.isBlank()) {
            try {
                Object parsed = Json.parseTree(existing);
                if (parsed instanceof Map<?, ?> map) {
                    map.forEach((key, value) -> metadata.put(String.valueOf(key), value));
                }
            } catch (RuntimeException ignored) {
                // Existing extractor metadata should be valid; retain provenance even if it is not.
            }
        }
        metadata.put("isSynthetic", true);
        metadata.put("generator", generator);
        metadata.put("generatorMode", generatorMode);
        metadata.put("generatedFrom", generatedFrom);
        metadata.put("confidence", confidence);
        metadata.put("bodyAvailable", bodyAvailable);
        return Json.writeCompact(metadata);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generator", generator);
        out.put("generator_mode", generatorMode);
        out.put("generated_from", generatedFrom);
        out.put("confidence", confidence);
        out.put("body_available", bodyAvailable);
        return out;
    }

    public static final class SyntheticOriginKey extends DataKey<SyntheticOrigin> {
        private SyntheticOriginKey() {}
    }
}
