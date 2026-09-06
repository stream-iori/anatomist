package com.anatomist.cli;

import com.anatomist.query.GenericSemanticRows.Site;
import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Command(name="trace", mixinStandardHelpOptions=true,
        description="Find one bounded call path from an input callable to an explicit end selector.",
        footer="%nAccepts: entity(callable)%nEmits: trace + evidence%n%nExample:%n  anatomist resolve 'p.A#start()' --kind callable --unique | anatomist trace --to 'p.B#end()'")
public final class TraceCommand extends TransformSemanticCommand {
    @Option(names="--to", required=true) String to;
    @Option(names="--max-depth", defaultValue="10") int maxDepth;
    @Option(names="--dispatch", defaultValue="resolved") String dispatch;
    @Override protected Set<String> acceptedInputRecords(){return Set.of("entity");}
    @Override protected Result execute(QueryService query,SemanticIdentity identity,SemanticStreamWriter writer){
        CliValidation.positive("--max-depth",maxDepth);dispatch=CliValidation.choice("--dispatch",dispatch,"resolved","possible");
        AtomicInteger seeds=new AtomicInteger(),emitted=new AtomicInteger();boolean[] complete={true};
        SemanticStreamReader.Summary input=readFrames(Set.of("entity"),acceptUnframed,identity,frame->{seeds.incrementAndGet();int count=0;boolean frameComplete=frame.evidence().complete();for(SemanticRecord record:frame.records()){SemanticRecord.Entity entity=(SemanticRecord.Entity)record;if(!"callable".equals(entity.kind()))throw new IllegalArgumentException("trace accepts callable entities; got "+entity.kind());List<Site> path=query.semanticCallPath(entity.id(),to,maxDepth,dispatch);if(!path.isEmpty()){String id="trace:sha256:"+SemanticIdentity.sha256(entity.id()+"\n"+to+"\n"+dispatch);String child=SemanticRecords.childSeed(frame.seedId(),"trace",id);Map<String,Object> out=SemanticRecords.common("trace",child,frame.seedId(),identity);out.put("id",id);out.put("start",entity.id());out.put("end_selector",to);out.put("dispatch",dispatch);out.put("hops",path.stream().map(TraceCommand::hop).toList());out.put("origin","derived");out.put("resolution_status","possible".equals(dispatch)?"heuristic":"exact");out.putAll(SemanticRecords.lineage(entity.raw()));writer.write(out);writer.write(SemanticRecords.seedEvidence(child,frame.seedId(),1,frameComplete,frameComplete?null:"UPSTREAM_INCOMPLETE",identity));count=1;emitted.incrementAndGet();}}writer.write(SemanticRecords.seedEvidence(frame.seedId(),null,count,frameComplete,frameComplete?null:"UPSTREAM_INCOMPLETE",identity));if(!frameComplete)complete[0]=false;});
        if(seeds.get()==0)throw new IllegalArgumentException("trace requires an entity stream on stdin");return new Result(seeds.get(),emitted.get(),input.complete()&&complete[0],false);
    }
    private static Map<String,Object> hop(Site row){Map<String,Object> out=new LinkedHashMap<>();out.put("source",row.source());out.put("target",row.target());out.put("relation",row.relation());out.put("producer_id",row.producerId());return out;}
}
