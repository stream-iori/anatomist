package com.anatomist.cli;

import com.anatomist.model.GraphConstants;
import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Command(modelTransformer = AgentHelp.class, name="references", mixinStandardHelpOptions=true,
        description = "Find source or configuration references to or from an entity.",
        footer = "%nBoundary: References do not prove runtime dependency or execution.%n%nExample:%n  anatomist pipeline -- resolve p.Service --kind type --unique --then references --direction incoming")
public final class ReferencesCommand extends TransformSemanticCommand {
    @Option(names="--direction", defaultValue="incoming", description="incoming: uses of the entity; outgoing: references made by it (default incoming).") String direction;
    @Option(names="--limit", defaultValue="50", description="Maximum reference sites per entity; >=1 (default 50).") int limit;
    @Override protected Set<String> acceptedInputRecords(){return Set.of("entity");}
    @Override protected Result execute(QueryService query, SemanticIdentity identity, SemanticStreamWriter writer){
        direction=CliValidation.choice("--direction",direction,"outgoing","incoming"); CliValidation.positive("--limit",limit);
        AtomicInteger seeds=new AtomicInteger(),emitted=new AtomicInteger(); boolean[] complete={true},truncated={false};
        SemanticStreamReader.Summary input=readFrames(Set.of("entity"),acceptUnframed,identity,frame->{
            seeds.incrementAndGet();int count=0;boolean frameComplete=frame.evidence().complete();boolean frameTruncated=false;
            for(SemanticRecord record:frame.records()){
                SemanticRecord.Entity entity=(SemanticRecord.Entity)record;
                var rows=query.semanticSites(entity.id(),direction,Set.of(GraphConstants.Relation.REFERENCES,GraphConstants.Relation.XML_REFERS_TO),limit+1);
                for(var row:rows){if(count>=limit){truncated[0]=true;frameTruncated=true;frameComplete=false;break;}String child=SemanticRecords.childSeed(frame.seedId(),"references",row.source()+row.target()+row.ordinal());writer.write(SiteRecordMaps.site("reference_site",row,child,frame.seedId(),entity.raw(),identity));writer.write(SemanticRecords.seedEvidence(child,frame.seedId(),1,true,null,identity));count++;emitted.incrementAndGet();if(row.target()==null)frameComplete=false;}
            }
            writer.write(SemanticRecords.seedEvidence(frame.seedId(),null,count,frameComplete,frameComplete?null:frameTruncated?"QUERY_LIMIT_TRUNCATED":"OPEN_WORLD_OR_HEURISTIC",frameTruncated,identity));if(!frameComplete)complete[0]=false;
        });
        if(seeds.get()==0)throw new IllegalArgumentException("references requires an entity stream on stdin");
        return new Result(seeds.get(),emitted.get(),input.complete()&&complete[0],truncated[0]);
    }
}
