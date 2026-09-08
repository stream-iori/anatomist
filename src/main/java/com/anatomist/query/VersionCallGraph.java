package com.anatomist.query;

import com.anatomist.query.JavaSemanticRows.*;
import java.util.*;
import java.util.function.Function;

/** Lazy reverse edges and dispatch evidence, isolated to one frozen query connection. */
final class VersionCallGraph implements Function<String,List<VersionRelationships.Relation>> {
    private final QueryService query;
    private final boolean dispatch;
    private final int maxDepth,limit,budget;
    private int states;
    private boolean truncated;
    private final Map<String,List<VersionRelationships.Relation>> recorded=new HashMap<>(),incoming=new HashMap<>();
    private final Map<String,DispatchResult> sites=new HashMap<>();
    private final Set<String> reasons=new TreeSet<>();

    VersionCallGraph(QueryService query,Map<String,VersionRelationships.Relation> relations,boolean dispatch) {
        this(query,relations,dispatch,20,50,100_000);
    }
    VersionCallGraph(QueryService query,Map<String,VersionRelationships.Relation> relations,boolean dispatch,int maxDepth,int limit,int budget) {
        this.query=query;this.dispatch=dispatch;this.maxDepth=maxDepth;this.limit=limit;this.budget=budget;
        for(var relation:relations.values()) if(relation.kind().equals("CALLS")) recorded.computeIfAbsent(relation.target(),k->new ArrayList<>()).add(relation);
        if(dispatch) { query.selectNodes(null,"ALL");reasons.add("DISPATCH_OPEN_WORLD"); }
    }
    Set<String> reasons() { return Set.copyOf(reasons); }
    boolean truncated() { return truncated; }
    int states() { return states; }
    @Override public List<VersionRelationships.Relation> apply(String target) {
        return incoming.computeIfAbsent(target,this::readIncoming);
    }
    private List<VersionRelationships.Relation> readIncoming(String target) {
        List<VersionRelationships.Relation> out=new ArrayList<>(recorded.getOrDefault(target,List.of()));
        if(!dispatch) return out;
        try(var cursor=query.dispatchCallSites(target)) {
            while(cursor.hasNext()) {
                if(states>=budget) { reasons.add("DISPATCH_STATE_LIMIT");truncated=true;break; }
                CallSiteRow site=cursor.next();states++;
                DispatchResult result=sites.get(site.id);
                if(result==null) {
                    result=query.dispatchDetailed(site.dispatchInput(),"auto","workspace-open",maxDepth,limit,budget-states);
                    states+=result.states();sites.put(site.id,result);
                }
                reasons.addAll(result.reasons());truncated |= result.truncated();
                for(var row:result.targets()) if(row.target().equals(target) && row.candidateKind().equals("possible")) {
                    Map<String,Object> fields=Map.of("source",site.callerId,"target",target,"relation","CALLS");
                    Map<String,Object> evidence=new LinkedHashMap<>();
                    evidence.put("candidate_kind","possible");evidence.put("call_site",site.id);
                    evidence.put("algorithm",row.algorithm());evidence.put("world",row.world());evidence.put("mechanism",row.mechanism());
                    evidence.put("resolution_status",row.resolutionStatus());evidence.put("reason",row.reason());
                    evidence.put("resolved_targets",site.targets.stream().map(CallSiteRow.Target::id).toList());
                    evidence.put("proof",row.proof().stream().map(p->Map.of("id",p.id(),"subject",p.subject(),"object",p.object(),"semantic",p.semantic(),"mechanism",p.mechanism())).toList());
                    evidence.put("type_proof",row.typeProof().stream().map(p->Map.of("id",p.id(),"subject",p.subject(),"object",p.object(),"semantic",p.semantic(),"mechanism",p.mechanism())).toList());
                    var location=Map.<String,Object>of("file",site.sourceFile,"line",site.beginLine,"column",site.beginColumn);
                    out.add(new VersionRelationships.Relation(fields,1,List.of(location),evidence));
                }
            }
        }
        return out;
    }
}
