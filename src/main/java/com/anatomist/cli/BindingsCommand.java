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
        description="Return cross-domain configuration/language bindings.",
        footer="%nAccepts: entity%nEmits: binding_relation + evidence%n%nExample:%n  anatomist search OrderService --kind type --format ndjson | anatomist resolve --unique | anatomist bindings --direction incoming --semantic realizes")
public final class BindingsCommand extends SemanticCommand {
    @Option(names="--direction", defaultValue="outgoing") String direction;
    @Option(names="--semantic", defaultValue="any") String semantic;
    @Option(names="--limit", defaultValue="50") int limit;
    @Override protected Set<String> acceptedInputRecords(){ return Set.of("entity"); }
    @Override protected Result execute(QueryService query, SemanticIdentity identity, SemanticStreamWriter writer){
        direction=CliValidation.choice("--direction", direction, "outgoing", "incoming");
        semantic=CliValidation.choice("--semantic", semantic, "any", "realizes", "wires", "parent", "factory");
        CliValidation.positive("--limit", limit); AtomicInteger seeds=new AtomicInteger(), emitted=new AtomicInteger();
        SemanticStreamReader.Summary input=SemanticStreamReader.readFrames(System.in, Set.of("entity"), acceptUnframed, identity, frame->{
            seeds.incrementAndGet(); int count=0;
            for(SemanticRecord record:frame.records()){
                SemanticRecord.Entity entity=(SemanticRecord.Entity)record;
                for(EdgeRow row:query.semanticBindings(entity.id(), direction, semantic, limit)){
                    String stable=row.source+"\n"+row.relation+"\n"+(row.isExternal?row.externalTargetFqn:row.target);
                    String id="binding:sha256:"+SemanticIdentity.sha256(stable);
                    String child=SemanticRecords.childSeed(frame.seedId(), "bindings", id);
                    Map<String,Object> out=SemanticRecords.common("binding_relation", child, frame.seedId(), identity);
                    out.put("id",id); out.put("semantic", semantic(row.relation)); out.put("mechanism",mechanism(row.relation));
                    out.put("subject",row.source); out.put("object",row.isExternal?row.externalTargetFqn:row.target);
                    out.put("external_object",row.isExternal); out.put("producer_id",row.producerId);
                    out.put("origin","CONFIGURED".equals(row.confidence)?"configured":"extracted");
                    out.put("resolution_status",row.isExternal?"heuristic":"exact"); out.putAll(SemanticRecords.lineage(entity.raw()));
                    writer.write(out); writer.write(SemanticRecords.seedEvidence(child, frame.seedId(),1,true,null,identity)); count++; emitted.incrementAndGet();
                }
            }
            boolean complete=frame.evidence().complete(); writer.write(SemanticRecords.seedEvidence(frame.seedId(),null,count,complete,complete?null:"UPSTREAM_INCOMPLETE",identity));
        });
        if(seeds.get()==0) throw new IllegalArgumentException("bindings requires an entity stream on stdin");
        return new Result(seeds.get(),emitted.get(),input.complete(),false);
    }
    private static String semantic(String relation){ return switch(relation){ case "DEFINED_BY"->"REALIZES"; case "PARENT_BEAN"->"INHERITS_CONFIGURATION"; case "FACTORY_BEAN"->"CREATED_BY"; default->"BINDS"; }; }
    private static String mechanism(String relation){ return switch(relation){ case "DEFINED_BY"->"spring.xml.class"; case "PARENT_BEAN"->"spring.xml.parent"; case "FACTORY_BEAN"->"spring.xml.factory"; case "INJECTS"->"spring.annotation.injection"; default->"spring.xml.ref"; }; }
}
