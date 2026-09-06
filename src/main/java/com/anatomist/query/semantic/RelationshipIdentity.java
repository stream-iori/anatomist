package com.anatomist.query.semantic;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/** Location-independent identity for comparing semantic relationships across indexes. */
public final class RelationshipIdentity {
    public static final String PREFIX = "rel:sha256:";

    private RelationshipIdentity() {}

    public static String of(String kind, Map<String, ?> fields) {
        Map<String, Object> canonical = new java.util.TreeMap<>();
        canonical.put("kind", value(kind));
        if (fields != null) fields.forEach((key, value) -> canonical.put(key, normalize(value)));
        return PREFIX + SemanticIdentity.sha256(com.anatomist.json.Json.writeCompact(canonical));
    }

    public static Map<String, Object> fields(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("relationship fields require key/value pairs");
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return out;
    }

    private static Object normalize(Object value) {
        if (value == null) return "";
        if (value instanceof Collection<?> collection) {
            TreeSet<String> sorted = new TreeSet<>();
            collection.forEach(item -> sorted.add(value(item)));
            return sorted;
        }
        if (value instanceof Boolean || value instanceof Number) return value;
        return value(value);
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
