package com.anatomist.query.semantic;

import java.util.Map;

import static com.anatomist.query.semantic.SemanticStreamReader.SemanticStreamException;

/** Hand-written record-dispatched decoder for semantic-stream/v1. */
public final class SemanticRecordCodec {
    private SemanticRecordCodec() {}

    public static SemanticRecord decode(Map<String, Object> raw) {
        String type = requiredString(raw, "record");
        SemanticRecord.Header header = new SemanticRecord.Header(type,
                string(raw, "seed_id"), string(raw, "parent_seed_id"),
                requiredString(raw, "index_revision_id"), string(raw, "source_snapshot_id"),
                requiredString(raw, "semantic_profile_id"));
        if (!"evidence".equals(type) && (header.seedId() == null || header.seedId().isBlank())) {
            throw invalid("MISSING_SEED_ID", "data record is missing seed_id");
        }
        return switch (type) {
            case "entity_candidate" -> new SemanticRecord.EntityCandidate(header,
                    requiredString(raw, "id"), string(raw, "kind"),
                    string(raw, "qualified_name"), raw);
            case "entity" -> new SemanticRecord.Entity(header, requiredString(raw, "id"),
                    string(raw, "kind"), string(raw, "qualified_name"), raw);
            case "type_relation" -> new SemanticRecord.TypeRelation(header,
                    requiredString(raw, "id"), requiredString(raw, "subject"),
                    requiredString(raw, "object"), requiredString(raw, "semantic"), raw);
            case "callable_relation" -> new SemanticRecord.CallableRelation(header,
                    requiredString(raw, "id"), requiredString(raw, "subject"),
                    requiredString(raw, "object"), requiredString(raw, "semantic"), raw);
            case "call_site" -> new SemanticRecord.CallSite(header, requiredString(raw, "id"),
                    requiredString(raw, "caller"), source(raw, true), raw);
            case "reference_site" -> new SemanticRecord.ReferenceSite(header,
                    requiredString(raw, "id"), requiredString(raw, "caller"), source(raw, false), raw);
            case "access_site" -> new SemanticRecord.AccessSite(header,
                    requiredString(raw, "id"), requiredString(raw, "caller"), source(raw, false), raw);
            case "control_region" -> new SemanticRecord.ControlRegion(header,
                    requiredString(raw, "id"), requiredString(raw, "caller"), source(raw, false), raw);
            case "dispatch_target" -> new SemanticRecord.DispatchTarget(header,
                    requiredString(raw, "id"), requiredString(raw, "caller"),
                    source(raw, false), raw);
            case "source_slice" -> new SemanticRecord.SourceSlice(header,
                    requiredString(raw, "id"), source(raw, false), raw);
            case "declaration", "annotation", "document_relation", "binding_relation", "trace",
                    "result_count", "project_summary", "package_summary", "package_dependency" ->
                    new SemanticRecord.Generic(header, requiredString(raw, "id"), raw);
            case "evidence" -> evidence(header, raw);
            default -> throw invalid("UNSUPPORTED_RECORD_TYPE",
                    "unsupported semantic record: " + type);
        };
    }

    private static SemanticRecord.Evidence evidence(SemanticRecord.Header header,
                                                     Map<String, Object> raw) {
        String scope = requiredString(raw, "scope");
        if (!"seed".equals(scope) && !"stream".equals(scope)) {
            throw invalid("INVALID_EVIDENCE_SCOPE", "evidence scope must be seed or stream");
        }
        if ("seed".equals(scope) && (header.seedId() == null || header.seedId().isBlank())) {
            throw invalid("MISSING_SEED_ID", "seed evidence is missing seed_id");
        }
        return new SemanticRecord.Evidence(header, scope, requiredString(raw, "status"),
                requiredString(raw, "coverage"), requiredBoolean(raw, "truncated"),
                requiredBoolean(raw, "negative_conclusion_safe"), raw);
    }

    private static SemanticRecord.SourceRange source(Map<String, Object> raw, boolean required) {
        Object value = raw.get("source");
        if (!(value instanceof Map<?, ?> source)) {
            if (required) throw invalid("INVALID_RECORD", "call_site requires a source object");
            return null;
        }
        String file = string(source, "file");
        Integer startLine = integer(source, "start_line");
        Integer startColumn = integer(source, "start_column");
        Integer endLine = integer(source, "end_line");
        Integer endColumn = integer(source, "end_column");
        Integer ordinal = integer(source, "ordinal");
        if (required && (file == null || startLine == null || startColumn == null
                || endLine == null || endColumn == null)) {
            throw invalid("INVALID_RECORD", "call_site source requires file and exact range");
        }
        if (required && (startLine < 1 || startColumn < 1 || endLine < 1 || endColumn < 1
                || endLine < startLine || (endLine.equals(startLine) && endColumn < startColumn))) {
            throw invalid("INVALID_RECORD", "call_site source range is invalid");
        }
        return new SemanticRecord.SourceRange(file, startLine, startColumn,
                endLine, endColumn, ordinal);
    }

    private static String requiredString(Map<?, ?> raw, String key) {
        String value = string(raw, key);
        if (value == null || value.isBlank()) throw invalid("INVALID_RECORD",
                "record requires non-empty " + key);
        return value;
    }

    private static String string(Map<?, ?> raw, String key) {
        Object value = raw.get(key);
        if (value == null) return null;
        if (value instanceof String text) return text;
        throw invalid("INVALID_RECORD", key + " must be a string");
    }

    private static Integer integer(Map<?, ?> raw, String key) {
        Object value = raw.get(key);
        if (value == null) return null;
        if (value instanceof Number number) return number.intValue();
        throw invalid("INVALID_RECORD", key + " must be an integer");
    }

    private static boolean requiredBoolean(Map<?, ?> raw, String key) {
        Object value = raw.get(key);
        if (!(value instanceof Boolean bool)) throw invalid("INVALID_RECORD",
                "record requires boolean " + key);
        return bool;
    }

    private static SemanticStreamException invalid(String code, String message) {
        return new SemanticStreamException(code, message);
    }
}
