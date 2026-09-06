package com.anatomist.query.semantic;

import com.anatomist.query.NodeRow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reflection-free semantic-stream record factory. */
public final class SemanticRecords {
    public static final String CONTRACT = "semantic-stream/v1";
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100_000;
    public static final int MAX_LINE_BYTES = 1024 * 1024;
    public static final int MAX_RECORDS = 1_000_000;
    public static final int MAX_RECORDS_PER_SEED = 100_000;
    public static final int MAX_SEEDS = 100_000;
    public static final int MAX_JSON_DEPTH = 64;

    private SemanticRecords() {}

    public static Map<String, Object> candidate(NodeRow node, String seed,
                                                 SemanticIdentity identity) {
        Map<String, Object> out = entityLike("entity_candidate", node, seed, identity);
        out.put("resolution_status", "ambiguous");
        return out;
    }

    public static Map<String, Object> entity(NodeRow node, String seed,
                                              String resolutionStatus,
                                              SemanticIdentity identity) {
        Map<String, Object> out = entityLike("entity", node, seed, identity);
        out.put("resolution_status", resolutionStatus);
        return out;
    }

    private static Map<String, Object> entityLike(String record, NodeRow node, String seed,
                                                   SemanticIdentity identity) {
        Map<String, Object> out = common(record, seed, null, identity);
        out.put("id", node.id);
        out.put("domain", node.domain == null ? domain(node.kind) : node.domain);
        if ("language".equals(out.get("domain"))) {
            String language = node.language == null
                    ? SemanticProviders.languageForProducer(node.producerId) : node.language;
            if (language != null) out.put("language", language);
        }
        String provider = node.providerId == null
                ? SemanticProviders.providerForProducer(node.producerId) : node.providerId;
        if (provider != null) out.put("provider_id", provider);
        out.put("kind", node.entityKind == null
                ? SemanticProviders.entityKind(node.producerId, node.kind) : node.entityKind);
        out.put("name", node.label);
        out.put("qualified_name", node.qualifiedName);
        out.put("module", node.module);
        out.put("scope", node.scope);
        out.put("producer_id", node.producerId == null ? "java-core" : node.producerId);
        out.put("origin", node.syntheticOrigin == null ? "extracted" : "derived");
        if (node.sourceFile != null && !node.sourceFile.isBlank()) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("file", node.sourceFile);
            Integer line = node.beginLine != null ? node.beginLine : line(node.sourceLocation);
            if (line != null) source.put("start_line", line);
            if (node.beginColumn != null) source.put("start_column", node.beginColumn);
            if (node.endLine != null) source.put("end_line", node.endLine);
            if (node.endColumn != null) source.put("end_column", node.endColumn);
            if (node.sourceOrdinal != null) source.put("ordinal", node.sourceOrdinal);
            out.put("source", source);
        }
        Map<String, Object> facets = new LinkedHashMap<>();
        facets.put("storage_kind", node.kind);
        String languageKind = node.languageKind == null
                ? SemanticProviders.languageKind(node.producerId, node.kind) : node.languageKind;
        if (languageKind != null) facets.put("language_kind", languageKind);
        if (node.syntheticOrigin != null) facets.put("synthetic_origin", node.syntheticOrigin);
        if (node.lombok != null) facets.put("lombok", node.lombok);
        if (!facets.isEmpty()) out.put("facets", facets);
        return out;
    }

    public static Map<String, Object> common(String record, String seed, String parentSeed,
                                              SemanticIdentity identity) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("record", record);
        out.put("contract", CONTRACT);
        if (seed != null) out.put("seed_id", seed);
        if (parentSeed != null) out.put("parent_seed_id", parentSeed);
        out.put("index_revision_id", identity.indexRevisionId());
        out.put("source_snapshot_id", identity.sourceSnapshotId());
        out.put("semantic_profile_id", identity.semanticProfileId());
        return out;
    }

    public static Map<String, Object> streamHeader(SemanticIdentity identity) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("record", "stream_header");
        out.put("contract", CONTRACT);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("index_revision_id", identity.indexRevisionId());
        values.put("source_snapshot_id", identity.sourceSnapshotId());
        values.put("semantic_profile_id", identity.semanticProfileId());
        out.put("identity", values);
        return out;
    }

    public static Map<String, Object> seedEvidence(String seed, String parentSeed,
                                                    int emitted, boolean complete,
                                                    String code, SemanticIdentity identity) {
        return seedEvidence(seed, parentSeed, emitted, complete, code, false, identity);
    }

    public static Map<String, Object> seedEvidence(String seed, String parentSeed,
                                                    int emitted, boolean complete,
                                                    String code, boolean truncated,
                                                    SemanticIdentity identity) {
        Map<String, Object> out = common("evidence", seed, parentSeed, identity);
        out.put("scope", "seed");
        out.put("status", emitted > 0 ? (complete ? "positive" : "partial")
                : (complete ? "empty" : "partial"));
        out.put("coverage", complete ? "complete" : "unknown");
        out.put("negative_conclusion_safe", complete && emitted == 0);
        out.put("emitted", emitted);
        out.put("truncated", truncated);
        if (code != null) out.put("code", code);
        return out;
    }

    public static Map<String, Object> streamEvidence(int seeds, int emitted, boolean complete,
                                                      SemanticIdentity identity) {
        return streamEvidence(seeds, emitted, complete, false, identity);
    }

    public static Map<String, Object> streamEvidence(int seeds, int emitted, boolean complete,
                                                      boolean truncated,
                                                      SemanticIdentity identity) {
        Map<String, Object> out = common("evidence", null, null, identity);
        out.put("scope", "stream");
        out.put("status", complete ? (emitted == 0 ? "empty" : "positive") : "partial");
        out.put("coverage", complete ? "complete" : "unknown");
        out.put("negative_conclusion_safe", complete && emitted == 0);
        out.put("seeds", Map.of("total", seeds));
        out.put("emitted", emitted);
        out.put("truncated", truncated);
        return out;
    }

    public static Map<String, Object> unsupportedEvidence(String seed, String parentSeed,
                                                           String operation,
                                                           SemanticIdentity identity) {
        return unsupportedEvidence(seed, parentSeed, operation, "java",
                SemanticProviders.providerForLanguage("java"), identity);
    }

    public static Map<String, Object> unsupportedEvidence(String seed, String parentSeed,
                                                           String operation, String language,
                                                           String providerId,
                                                           SemanticIdentity identity) {
        Map<String, Object> out = common("evidence", seed, parentSeed, identity);
        out.put("scope", "seed");
        out.put("status", "unsupported");
        out.put("coverage", "unsupported");
        out.put("negative_conclusion_safe", false);
        out.put("emitted", 0);
        out.put("truncated", false);
        out.put("code", "UNSUPPORTED_CAPABILITY");
        out.put("operation", operation);
        if (providerId != null) out.put("provider_id", providerId);
        if (language != null) out.put("language", language);
        out.put("limitations", List.of(Map.of("code", "OPERATION_UNAVAILABLE")));
        return out;
    }

    public static Map<String, Object> lineage(Map<String, Object> input) {
        Object id = input.get("id");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("record", input.get("record"));
        if (id != null) item.put("id", id);
        return Map.of("derived_from", List.of(item));
    }

    public static String childSeed(String parent, String operation, String stableSubject) {
        return "seed:" + SemanticIdentity.sha256(parent + "\n" + operation + "\n"
                + stableSubject).substring(0, 24);
    }

    public static String rootSeed(String operation, String selector) {
        return "seed:" + SemanticIdentity.sha256(operation + "\n" + selector).substring(0, 24);
    }

    public static Integer line(String location) {
        if (location == null) return null;
        int start = location.indexOf('L');
        if (start < 0) return null;
        int end = start + 1;
        while (end < location.length() && Character.isDigit(location.charAt(end))) end++;
        if (end == start + 1) return null;
        try { return Integer.parseInt(location.substring(start + 1, end)); }
        catch (NumberFormatException ignored) { return null; }
    }

    public static String kind(String storageKind) {
        if (storageKind == null) return "entity";
        return switch (storageKind) {
            case "CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION", "ANONYMOUS_CLASS",
                    "EXTERNAL_CLASS" -> "type";
            case "METHOD", "CONSTRUCTOR", "LAMBDA", "METHOD_REF" -> "callable";
            case "FIELD", "ENUM_CONSTANT" -> "value";
            case "BEAN" -> "component";
            case "ARTIFACT" -> "artifact";
            case "XML_PROPERTY" -> "property";
            case "XML_ENTRY", "XML_LIST", "XML_MAP", "XML_VALUE", "XML_REF", "XML_IDREF",
                    "XML_NULL", "XML_CONSTRUCTOR_ARG", "XML_CALLABLE_REF" -> "config_entity";
            default -> storageKind.toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static String domain(String storageKind) {
        return storageKind != null && (storageKind.equals("ARTIFACT") || storageKind.equals("BEAN") || storageKind.startsWith("XML_"))
                ? "configuration" : "language";
    }
}
