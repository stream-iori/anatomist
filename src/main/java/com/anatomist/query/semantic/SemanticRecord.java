package com.anatomist.query.semantic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Typed, reflection-free view of one semantic-stream record. */
public sealed interface SemanticRecord permits SemanticRecord.EntityCandidate,
        SemanticRecord.Entity, SemanticRecord.TypeRelation, SemanticRecord.CallableRelation,
        SemanticRecord.CallSite, SemanticRecord.ReferenceSite, SemanticRecord.AccessSite,
        SemanticRecord.ControlRegion, SemanticRecord.DispatchTarget,
        SemanticRecord.SourceSlice, SemanticRecord.Generic, SemanticRecord.Evidence {

    Header header();
    Map<String, Object> raw();

    default String type() { return header().record(); }
    default String seedId() { return header().seedId(); }

    record Header(String record, String seedId, String parentSeedId,
                  String indexRevisionId, String sourceSnapshotId,
                  String semanticProfileId) {}

    record SourceRange(String file, Integer startLine, Integer startColumn,
                       Integer endLine, Integer endColumn, Integer ordinal) {}

    record EntityCandidate(Header header, String id, String kind,
                           String qualifiedName, Map<String, Object> raw)
            implements SemanticRecord {
        public EntityCandidate { raw = immutable(raw); }
    }

    record Entity(Header header, String id, String kind,
                  String qualifiedName, Map<String, Object> raw)
            implements SemanticRecord {
        public Entity { raw = immutable(raw); }
    }

    record TypeRelation(Header header, String id, String subject, String object,
                        String semantic, Map<String, Object> raw) implements SemanticRecord {
        public TypeRelation { raw = immutable(raw); }
    }

    record CallableRelation(Header header, String id, String subject, String object,
                            String semantic, Map<String, Object> raw) implements SemanticRecord {
        public CallableRelation { raw = immutable(raw); }
    }

    record CallSite(Header header, String id, String caller, SourceRange source,
                    Map<String, Object> raw) implements SemanticRecord {
        public CallSite { raw = immutable(raw); }
    }

    record ReferenceSite(Header header, String id, String caller, SourceRange source,
                         Map<String, Object> raw) implements SemanticRecord {
        public ReferenceSite { raw = immutable(raw); }
    }

    record AccessSite(Header header, String id, String caller, SourceRange source,
                      Map<String, Object> raw) implements SemanticRecord {
        public AccessSite { raw = immutable(raw); }
    }

    record ControlRegion(Header header, String id, String caller, SourceRange source,
                         Map<String, Object> raw) implements SemanticRecord {
        public ControlRegion { raw = immutable(raw); }
    }

    record DispatchTarget(Header header, String id, String caller, SourceRange source,
                          Map<String, Object> raw) implements SemanticRecord {
        public DispatchTarget { raw = immutable(raw); }
    }

    record SourceSlice(Header header, String id, SourceRange source,
                       Map<String, Object> raw) implements SemanticRecord {
        public SourceSlice { raw = immutable(raw); }
    }

    record Generic(Header header, String id, Map<String, Object> raw) implements SemanticRecord {
        public Generic { raw = immutable(raw); }
    }

    record Evidence(Header header, String scope, String status, String coverage,
                    boolean truncated, boolean negativeConclusionSafe,
                    Map<String, Object> raw) implements SemanticRecord {
        public Evidence { raw = immutable(raw); }

        public boolean complete() {
            return "complete".equals(coverage) && !truncated && !"partial".equals(status)
                    && !"unsupported".equals(status);
        }
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
