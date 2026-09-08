package com.anatomist.cli;

import com.anatomist.query.NodeRow;
import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.*;
import picocli.CommandLine.Command;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Command(modelTransformer = AgentHelp.class, name="describe", mixinStandardHelpOptions=true,
        description = "Read declaration metadata for an entity.",
        footer = "%nBoundary: Declaration metadata only. Source accepts this output; members requires the original entity.%n%nExample:%n  anatomist pipeline -- resolve p.Service --kind type --unique --then describe")
public final class DescribeCommand extends TransformSemanticCommand {
    @Override protected Set<String> acceptedInputRecords(){return Set.of("entity");}
    @Override protected Result execute(QueryService query,SemanticIdentity identity,SemanticStreamWriter writer){
        AtomicInteger seeds=new AtomicInteger(),emitted=new AtomicInteger();SemanticStreamReader.Summary input=readFrames(Set.of("entity"),acceptUnframed,identity,frame->{seeds.incrementAndGet();int count=0;for(SemanticRecord record:frame.records()){SemanticRecord.Entity entity=(SemanticRecord.Entity)record;NodeRow node=query.resolveNode(entity.id()).requireUnique();String child=SemanticRecords.childSeed(frame.seedId(),"describe",node.id);Map<String,Object> out=SemanticRecords.common("declaration",child,frame.seedId(),identity);out.put("id","declaration:sha256:"+SemanticIdentity.sha256(node.id));out.put("entity",node.id);out.put("domain",entity.raw().get("domain"));out.put("language",entity.raw().get("language"));out.put("kind",entity.kind());out.put("name",node.label);out.put("qualified_name",node.qualifiedName);out.put("module",node.module);out.put("scope",node.scope);out.put("producer_id",node.producerId);out.put("origin","extracted");out.put("resolution_status","exact");Object source=entity.raw().get("source");if(source!=null)out.put("source",source);Object facets=entity.raw().get("facets");if(facets!=null)out.put("facets",facets);out.putAll(SemanticRecords.lineage(entity.raw()));writer.write(out);writer.write(SemanticRecords.seedEvidence(child,frame.seedId(),1,true,null,identity));count++;emitted.incrementAndGet();}boolean complete=frame.evidence().complete();writer.write(SemanticRecords.seedEvidence(frame.seedId(),null,count,complete,complete?null:"UPSTREAM_INCOMPLETE",identity));});if(seeds.get()==0)throw new IllegalArgumentException("describe requires an entity stream on stdin");return new Result(seeds.get(),emitted.get(),input.complete(),false);
    }
}
