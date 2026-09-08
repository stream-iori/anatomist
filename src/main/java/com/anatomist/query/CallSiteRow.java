package com.anatomist.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Canonical source call site with zero or more static resolution targets. */
public final class CallSiteRow {
    public String id;
    public String callerId;
    public String sourceFile;
    public int beginLine;
    public int beginColumn;
    public int endLine;
    public int endColumn;
    public int ordinal;
    public String context;
    public String syntaxTarget;
    public String receiverStaticType;
    public String dispatchKind;
    public String metadata;
    public String origin;
    public String resolutionStatus;
    public String producerId;
    public final List<Target> targets = new ArrayList<>();

    public Map<String,Object> dispatchInput() {
        Map<String,Object> raw=new LinkedHashMap<>();
        raw.put("id",id);raw.put("caller",callerId);
        raw.put("dispatch_kind",dispatchKind==null?"unknown":dispatchKind.toLowerCase(java.util.Locale.ROOT));
        if(receiverStaticType!=null) raw.put("receiver_static_type",receiverStaticType);
        raw.put("resolved_targets",targets.stream().map(t->{
            Map<String,Object> target=new LinkedHashMap<>();target.put("id",t.id());target.put("external",t.external());
            target.put("resolution_status",t.resolutionStatus());if(t.qualifiedName()!=null) target.put("qualified_name",t.qualifiedName());return target;
        }).toList());return raw;
    }

    public record Target(String id, String qualifiedName, boolean external,
                         String resolutionStatus, String confidence,
                         String producerId) {}
}
