package com.anatomist.cli;

import com.anatomist.model.GraphConstants;
import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Command(name="sites-in", mixinStandardHelpOptions=true,
        description="Enumerate indexed call/access sites inside one control region.",
        footer="%nAccepts: control_region%nEmits: call_site | access_site + evidence%n%nExample:%n  anatomist resolve 'p.Type#run()' --kind callable --unique | anatomist regions | anatomist sites-in --record call_site")
public final class SitesInCommand extends SemanticCommand {
    @Option(names="--record", defaultValue="all") String recordType;
    @Option(names="--limit", defaultValue="50") int limit;
    @Override protected Set<String> acceptedInputRecords(){return Set.of("control_region");}
    @Override protected Result execute(QueryService query,SemanticIdentity identity,SemanticStreamWriter writer){
        recordType=CliValidation.choice("--record",recordType,"call_site","access_site","all");CliValidation.positive("--limit",limit);
        AtomicInteger seeds=new AtomicInteger(),emitted=new AtomicInteger();boolean[] complete={true},truncated={false};
        SemanticStreamReader.Summary input=readFrames(Set.of("control_region"),acceptUnframed,identity,frame->{seeds.incrementAndGet();int count=0;boolean frameComplete=frame.evidence().complete();boolean frameTruncated=false;for(SemanticRecord record:frame.records()){SemanticRecord.ControlRegion region=(SemanticRecord.ControlRegion)record;String context=String.valueOf(region.raw().get("context"));Set<String> relations="call_site".equals(recordType)?Set.of(GraphConstants.Relation.CALLS):"access_site".equals(recordType)?Set.of(GraphConstants.Relation.READS,GraphConstants.Relation.WRITES):Set.of(GraphConstants.Relation.CALLS,GraphConstants.Relation.READS,GraphConstants.Relation.WRITES);for(var row:query.semanticSites(region.caller(),"outgoing",relations,SemanticRecords.MAX_LIMIT)){if(!context.equals(row.context()))continue;if(count>=limit){truncated[0]=true;frameTruncated=true;frameComplete=false;break;}String outputRecord=GraphConstants.Relation.CALLS.equals(row.relation())?"call_site":"access_site";String child=SemanticRecords.childSeed(frame.seedId(),"sites-in",row.source()+row.relation()+row.ordinal());writer.write(SiteRecordMaps.site(outputRecord,row,child,frame.seedId(),region.raw(),identity));writer.write(SemanticRecords.seedEvidence(child,frame.seedId(),1,true,null,identity));count++;emitted.incrementAndGet();}}writer.write(SemanticRecords.seedEvidence(frame.seedId(),null,count,frameComplete,frameComplete?null:frameTruncated?"QUERY_LIMIT_TRUNCATED":"UPSTREAM_INCOMPLETE",frameTruncated,identity));if(!frameComplete)complete[0]=false;});
        if(seeds.get()==0)throw new IllegalArgumentException("sites-in requires a control_region stream on stdin");return new Result(seeds.get(),emitted.get(),input.complete()&&complete[0],truncated[0]);
    }
}
