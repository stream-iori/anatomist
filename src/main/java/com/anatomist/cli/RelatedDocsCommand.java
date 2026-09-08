package com.anatomist.cli;

import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Command(modelTransformer = AgentHelp.class, name="related-docs", mixinStandardHelpOptions=true,
        description = "Locate project documents related to an entity.",
        footer = "%nBoundary: Returns document locations/associations, not full document contents. Associations may be heuristic.%n%nExample:%n  anatomist pipeline -- resolve p.Service --kind type --unique --then related-docs")
public final class RelatedDocsCommand extends TransformSemanticCommand {
    @Option(names="--limit",defaultValue="20", description="Maximum related documents per entity; >=1 (default 20).")int limit;
    @Override protected Set<String> acceptedInputRecords(){return Set.of("entity");}
    @Override protected Result execute(QueryService query,SemanticIdentity identity,SemanticStreamWriter writer){CliValidation.positive("--limit",limit);AtomicInteger seeds=new AtomicInteger(),emitted=new AtomicInteger();boolean[] allComplete={true},anyTruncated={false};SemanticStreamReader.Summary input=readFrames(Set.of("entity"),acceptUnframed,identity,frame->{seeds.incrementAndGet();int count=0;boolean frameTruncated=false;for(SemanticRecord record:frame.records()){SemanticRecord.Entity entity=(SemanticRecord.Entity)record;var docs=query.searchRelatedDocs(String.valueOf(entity.raw().get("name")),entity.qualifiedName());for(var doc:docs){if(count>=limit){frameTruncated=true;break;}String id="docrel:sha256:"+SemanticIdentity.sha256(entity.id()+"\n"+doc.path);String child=SemanticRecords.childSeed(frame.seedId(),"related-docs",id);Map<String,Object> out=SemanticRecords.common("document_relation",child,frame.seedId(),identity);out.put("id",id);out.put("relationship_id",RelationshipIdentity.of("document_relation",RelationshipIdentity.fields("provider","documentation-index","semantic","documents","subject",entity.id(),"object",doc.path,"doc_type",doc.docType)));out.put("entity",entity.id());out.put("path",doc.path);out.put("title",doc.title);out.put("doc_type",doc.docType);out.put("producer_id","documentation-index");out.put("origin","derived");out.put("resolution_status","heuristic");out.putAll(SemanticRecords.lineage(entity.raw()));writer.write(out);writer.write(SemanticRecords.seedEvidence(child,frame.seedId(),1,true,null,identity));count++;emitted.incrementAndGet();}}boolean complete=frame.evidence().complete()&&!frameTruncated;String code=complete?null:frameTruncated?"QUERY_LIMIT_TRUNCATED":"UPSTREAM_INCOMPLETE";writer.write(SemanticRecords.seedEvidence(frame.seedId(),null,count,complete,code,frameTruncated,identity));if(!complete)allComplete[0]=false;if(frameTruncated)anyTruncated[0]=true;});if(seeds.get()==0)throw new IllegalArgumentException("related-docs requires an entity stream on stdin");return new Result(seeds.get(),emitted.get(),input.complete()&&allComplete[0],anyTruncated[0]);}
}
