package com.anatomist.cli;

import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.*;
import picocli.CommandLine.Command;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Command(name="annotations", mixinStandardHelpOptions=true,
        description="Return source annotation facts for an entity.",
        footer="%nAccepts: entity%nEmits: annotation + evidence")
public final class AnnotationsCommand extends SemanticCommand {
    @Override protected Set<String> acceptedInputRecords(){return Set.of("entity");}
    @Override protected Result execute(QueryService query,SemanticIdentity identity,SemanticStreamWriter writer){AtomicInteger seeds=new AtomicInteger(),emitted=new AtomicInteger();SemanticStreamReader.Summary input=readFrames(Set.of("entity"),acceptUnframed,identity,frame->{seeds.incrementAndGet();int count=0;for(SemanticRecord record:frame.records()){SemanticRecord.Entity entity=(SemanticRecord.Entity)record;for(Map<String,Object> annotation:query.context(entity.id(),0).annotations){String name=String.valueOf(annotation.get("annotation_fqn"));String id="annotation:sha256:"+SemanticIdentity.sha256(entity.id()+"\n"+name+"\n"+annotation.get("attributes"));String child=SemanticRecords.childSeed(frame.seedId(),"annotations",id);Map<String,Object> out=SemanticRecords.common("annotation",child,frame.seedId(),identity);out.put("id",id);out.put("entity",entity.id());out.put("name",name);if(annotation.get("attributes")!=null)out.put("attributes",annotation.get("attributes"));out.put("producer_id",annotation.get("producer_id"));out.put("origin","extracted");out.put("resolution_status","exact");out.putAll(SemanticRecords.lineage(entity.raw()));writer.write(out);writer.write(SemanticRecords.seedEvidence(child,frame.seedId(),1,true,null,identity));count++;emitted.incrementAndGet();}}boolean complete=frame.evidence().complete();writer.write(SemanticRecords.seedEvidence(frame.seedId(),null,count,complete,complete?null:"UPSTREAM_INCOMPLETE",identity));});if(seeds.get()==0)throw new IllegalArgumentException("annotations requires an entity stream on stdin");return new Result(seeds.get(),emitted.get(),input.complete(),false);}
}
