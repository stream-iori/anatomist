package com.anatomist.cli;

import com.anatomist.query.JavaSemanticRows.CallableRelation;
import com.anatomist.query.JavaSemanticRows.DispatchTarget;
import com.anatomist.query.JavaSemanticRows.TypeRelation;
import com.anatomist.query.semantic.SemanticIdentity;
import com.anatomist.query.semantic.SemanticRecords;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JavaSemanticRecordMaps {
    private JavaSemanticRecordMaps() {}

    static Map<String, Object> typeRelation(TypeRelation row, String seed, String parent,
                                            Map<String, Object> input,
                                            SemanticIdentity identity) {
        Map<String, Object> out = SemanticRecords.common("type_relation", seed, parent, identity);
        out.put("id", row.id());
        out.put("semantic", row.semantic().toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
        out.put("mechanism", row.mechanism());
        out.put("subject", row.subject());
        out.put("object", row.object());
        out.put("subject_name", row.subjectName());
        out.put("object_name", row.objectName());
        out.put("external_object", row.externalObject());
        out.put("declared", row.declared());
        out.put("producer_id", "java-core");
        out.put("origin", row.origin());
        out.put("resolution_status", row.resolutionStatus());
        if (row.confidence() != null) out.put("confidence", row.confidence());
        out.putAll(SemanticRecords.lineage(input));
        return out;
    }

    static Map<String, Object> callableRelation(CallableRelation row, String seed, String parent,
                                                Map<String, Object> input,
                                                SemanticIdentity identity) {
        Map<String, Object> out = SemanticRecords.common("callable_relation", seed, parent, identity);
        out.put("id", row.id());
        out.put("semantic", row.semantic());
        out.put("mechanism", row.mechanism());
        out.put("subject", row.subject());
        out.put("object", row.object());
        out.put("subject_name", row.subjectName());
        out.put("object_name", row.objectName());
        out.put("external_object", row.externalObject());
        out.put("producer_id", "java-core");
        out.put("origin", row.origin());
        out.put("resolution_status", row.resolutionStatus());
        if (row.confidence() != null) out.put("confidence", row.confidence());
        out.putAll(SemanticRecords.lineage(input));
        return out;
    }

    static Map<String, Object> dispatch(DispatchTarget row, String seed, String parent,
                                        Map<String, Object> input,
                                        SemanticIdentity identity) {
        Map<String, Object> out = SemanticRecords.common("dispatch_target", seed, parent, identity);
        out.put("id", row.id());
        out.put("call_site", row.callSite());
        out.put("caller", row.caller());
        out.put("target", row.target());
        if (row.targetName() != null) out.put("target_name", row.targetName());
        out.put("candidate_kind", row.candidateKind());
        out.put("mechanism", row.mechanism());
        out.put("executable", row.executable());
        out.put("instantiability", row.instantiability());
        out.put("algorithm", row.algorithm());
        out.put("world", row.world());
        out.put("producer_id", "java-dispatch");
        out.put("origin", "resolved".equals(row.candidateKind()) ? "extracted" : "derived");
        out.put("resolution_status", row.resolutionStatus());
        out.put("reason", row.reason());
        if (!row.proof().isEmpty()) out.put("proof", row.proof().stream()
                .map(JavaSemanticRecordMaps::proof).toList());
        Object source = input.get("source");
        if (source != null) out.put("source", source);
        out.putAll(SemanticRecords.lineage(input));
        return out;
    }

    static List<Map<String, Object>> proof(List<TypeRelation> proof) {
        return proof.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id());
            item.put("semantic", row.semantic().toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
            item.put("mechanism", row.mechanism());
            item.put("subject", row.subject());
            item.put("object", row.object());
            return item;
        }).toList();
    }

    private static Map<String, Object> proof(CallableRelation row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.id());
        item.put("semantic", row.semantic());
        item.put("mechanism", row.mechanism());
        item.put("subject", row.subject());
        item.put("object", row.object());
        return item;
    }
}
