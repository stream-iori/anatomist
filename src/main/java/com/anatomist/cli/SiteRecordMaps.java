package com.anatomist.cli;

import com.anatomist.query.GenericSemanticRows.Site;
import com.anatomist.query.semantic.SemanticIdentity;
import com.anatomist.query.semantic.SemanticRecords;

import java.util.LinkedHashMap;
import java.util.Map;

final class SiteRecordMaps {
    private SiteRecordMaps() {}

    static Map<String,Object> site(String record, Site row, String seed, String parent,
                                   Map<String,Object> input, SemanticIdentity identity) {
        String target = row.target() == null ? row.externalTarget() : row.target();
        Map<String,Object> out = SemanticRecords.common(record, seed, parent, identity);
        out.put("id", record + ":sha256:" + SemanticIdentity.sha256(row.source() + "\n"
                + row.relation() + "\n" + target + "\n" + row.sourceFile() + "\n"
                + row.beginLine() + ":" + row.beginColumn() + ":" + row.ordinal()));
        out.put("caller", row.source());
        out.put("target", target);
        out.put("semantic", row.relation());
        out.put("external_target", row.target() == null);
        out.put("producer_id", row.producerId());
        out.put("origin", "CONFIGURED".equals(row.confidence()) ? "configured" : "extracted");
        out.put("resolution_status", row.target() == null || row.resolution() != null ? "heuristic" : "exact");
        if (row.context() != null) out.put("context", row.context());
        if ("call_site".equals(record)) {
            Map<String,Object> resolved = new LinkedHashMap<>();
            resolved.put("id", target); resolved.put("external", row.target() == null);
            resolved.put("resolution_status", row.target() == null ? "heuristic" : "exact");
            out.put("resolved_targets", java.util.List.of(resolved));
            out.put("resolved_target", target);
            out.put("dispatch_kind", "unknown");
        }
        if (row.sourceFile() != null) {
            Map<String,Object> source = new LinkedHashMap<>(); source.put("file", row.sourceFile());
            if (row.beginLine()!=null) source.put("start_line",row.beginLine());
            if (row.beginColumn()!=null) source.put("start_column",row.beginColumn());
            if (row.endLine()!=null) source.put("end_line",row.endLine());
            if (row.endColumn()!=null) source.put("end_column",row.endColumn());
            if (row.ordinal()!=null) source.put("ordinal",row.ordinal()); out.put("source",source);
        }
        out.putAll(SemanticRecords.lineage(input)); return out;
    }
}
