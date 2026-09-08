package com.anatomist.cli;

import com.anatomist.model.GraphConstants;
import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Command(modelTransformer = AgentHelp.class, name="accesses", mixinStandardHelpOptions=true,
        description = "Find source locations that read or write a field.",
        footer = "%nBoundary: Source read/write locations, not ownership or runtime values.%n%nExample:%n  anatomist pipeline -- resolve 'p.Service#value' --kind value --unique --then accesses --mode all")
public final class AccessesCommand extends TransformSemanticCommand {
    @Option(names="--mode", defaultValue="all", description="Access kind: reads | writes | all (default all).") String mode;
    @Option(names="--limit", defaultValue="50", description="Maximum access sites per seed; >=1 (default 50).") int limit;
    @Override protected Set<String> acceptedInputRecords(){return Set.of("entity");}
    @Override protected Result execute(QueryService query,SemanticIdentity identity,SemanticStreamWriter writer){
        mode=CliValidation.choice("--mode",mode,"reads","writes","all");CliValidation.positive("--limit",limit);
        Set<String> relations="reads".equals(mode)?Set.of(GraphConstants.Relation.READS):"writes".equals(mode)?Set.of(GraphConstants.Relation.WRITES):Set.of(GraphConstants.Relation.READS,GraphConstants.Relation.WRITES);
        AtomicInteger seeds=new AtomicInteger(),emitted=new AtomicInteger(); boolean[] complete={true},truncated={false};
        SemanticStreamReader.Summary input=readFrames(Set.of("entity"),acceptUnframed,identity,frame->{seeds.incrementAndGet();int count=0;boolean frameComplete=frame.evidence().complete();boolean frameTruncated=false;for(SemanticRecord record:frame.records()){SemanticRecord.Entity entity=(SemanticRecord.Entity)record;if(!"value".equals(entity.kind()))throw new IllegalArgumentException("accesses accepts value entities; got "+entity.kind());for(var row:query.semanticSites(entity.id(),"incoming",relations,limit+1)){if(count>=limit){truncated[0]=true;frameTruncated=true;frameComplete=false;break;}String child=SemanticRecords.childSeed(frame.seedId(),"accesses",row.source()+row.relation()+row.ordinal());writer.write(SiteRecordMaps.site("access_site",row,child,frame.seedId(),entity.raw(),identity));writer.write(SemanticRecords.seedEvidence(child,frame.seedId(),1,true,null,identity));count++;emitted.incrementAndGet();}}writer.write(SemanticRecords.seedEvidence(frame.seedId(),null,count,frameComplete,frameComplete?null:frameTruncated?"QUERY_LIMIT_TRUNCATED":"UPSTREAM_INCOMPLETE",frameTruncated,identity));if(!frameComplete)complete[0]=false;});
        if(seeds.get()==0)throw new IllegalArgumentException("accesses requires an entity stream on stdin");return new Result(seeds.get(),emitted.get(),input.complete()&&complete[0],truncated[0]);
    }
}
