package com.anatomist.cli;

import com.anatomist.query.EdgeRow;
import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Command(name="bindings", mixinStandardHelpOptions=true,
        description="Return cross-domain configuration/language bindings, including configured member references.",
        footer="%nAccepts: entity%nEmits: binding_relation + evidence%nOperation: bindings; inspect with: anatomist operations bindings%n%nExamples:%n  anatomist search OrderService --kind type --format ndjson | anatomist resolve --unique | anatomist bindings --direction incoming --semantic realizes%n  anatomist search CheckoutBean --kind component --format ndjson | anatomist resolve --unique | anatomist bindings --semantic member")
public final class BindingsCommand extends SemanticCommand {
    @Option(names="--direction", defaultValue="outgoing") String direction;
    @Option(names="--semantic", defaultValue="any") String semantic;
    @Option(names="--limit", defaultValue="50") int limit;
    @Override protected Set<String> acceptedInputRecords(){ return Set.of("entity"); }
    @Override protected Result execute(QueryService query, SemanticIdentity identity, SemanticStreamWriter writer){
        direction=CliValidation.choice("--direction", direction, "outgoing", "incoming");
        semantic=CliValidation.choice("--semantic", semantic, "any", "realizes", "wires", "parent", "factory", "member");
        CliValidation.positive("--limit", limit); AtomicInteger seeds=new AtomicInteger(), emitted=new AtomicInteger();
        SemanticStreamReader.Summary input=readFrames(Set.of("entity"), acceptUnframed, identity, frame->{
            seeds.incrementAndGet(); int count=0;
            for(SemanticRecord record:frame.records()){
                SemanticRecord.Entity entity=(SemanticRecord.Entity)record;
                for(EdgeRow row:query.semanticBindings(entity.id(), direction, semantic, limit)){
                    Map<String,Object> metadata=metadata(row.metadata);
                    String role=string(metadata.get("role")); String configuredMechanism=string(metadata.get("mechanism"));
                    String stable=row.source+"\n"+row.relation+"\n"+role+"\n"+configuredMechanism+"\n"+(Boolean.TRUE.equals(row.isExternal)?row.externalTargetFqn:row.target);
                    String id="binding:sha256:"+SemanticIdentity.sha256(stable);
                    String child=SemanticRecords.childSeed(frame.seedId(), "bindings", id);
                    Map<String,Object> out=SemanticRecords.common("binding_relation", child, frame.seedId(), identity);
                    out.put("id",id); out.put("semantic", semantic(row.relation)); out.put("mechanism",configuredMechanism==null?mechanism(row.relation):configuredMechanism);
                    String provider=SemanticProviders.providerForProducer(row.producerId); String language=SemanticProviders.languageForProducer(row.producerId);
                    out.put("relationship_id",RelationshipIdentity.of("binding_relation",RelationshipIdentity.fields("provider",provider,"language",language,"semantic",semantic(row.relation),"mechanism",configuredMechanism==null?mechanism(row.relation):configuredMechanism,"role",role,"subject",row.source,"object",Boolean.TRUE.equals(row.isExternal)?row.externalTargetFqn:row.target,"external",row.isExternal)));
                    if(role!=null)out.put("role",role); if(metadata.get("symbolRef")!=null)out.put("symbol_ref",metadata.get("symbolRef"));
                    if(role!=null)out.put("candidate_group","binding-group:sha256:"+SemanticIdentity.sha256(row.source+"\n"+role+"\n"+configuredMechanism));
                    if(metadata.get("candidateIndex")!=null)out.put("candidate_index",metadata.get("candidateIndex"));
                    if(metadata.get("candidateCount")!=null)out.put("candidate_count",metadata.get("candidateCount"));
                    out.put("subject",row.source); out.put("object",Boolean.TRUE.equals(row.isExternal)?row.externalTargetFqn:row.target);
                    out.put("external_object",row.isExternal); out.put("producer_id",row.producerId);
                    out.put("origin",metadata.isEmpty()&&!"CONFIGURED".equals(row.confidence)?"extracted":"configured");
                    out.put("resolution_status",metadata.getOrDefault("resolutionStatus",Boolean.TRUE.equals(row.isExternal)?"unresolved":"exact")); out.putAll(SemanticRecords.lineage(entity.raw()));
                    writer.write(out); writer.write(SemanticRecords.seedEvidence(child, frame.seedId(),1,true,null,identity)); count++; emitted.incrementAndGet();
                }
            }
            boolean complete=frame.evidence().complete(); writer.write(SemanticRecords.seedEvidence(frame.seedId(),null,count,complete,complete?null:"UPSTREAM_INCOMPLETE",identity));
        });
        if(seeds.get()==0) throw new IllegalArgumentException("bindings requires an entity stream on stdin");
        return new Result(seeds.get(),emitted.get(),input.complete(),false);
    }
    private static String semantic(String relation){ return switch(relation){ case "DEFINED_BY"->"REALIZES"; case "BINDS_TO"->"MEMBER"; case "PARENT_BEAN"->"INHERITS_CONFIGURATION"; case "FACTORY_BEAN"->"CREATED_BY"; default->"BINDS"; }; }
    private static String mechanism(String relation){ return switch(relation){ case "DEFINED_BY"->"spring.xml.class"; case "BINDS_TO"->"configured.member"; case "PARENT_BEAN"->"spring.xml.parent"; case "FACTORY_BEAN"->"spring.xml.factory"; case "INJECTS"->"spring.annotation.injection"; default->"spring.xml.ref"; }; }
    @SuppressWarnings("unchecked") private static Map<String,Object> metadata(String value){if(value==null||value.isBlank())return Map.of();try{Object tree=com.anatomist.json.Json.parseTree(value);return tree instanceof Map<?,?> map?(Map<String,Object>)map:Map.of();}catch(RuntimeException ignored){return Map.of();}}
    private static String string(Object value){return value==null?null:String.valueOf(value);}
}
