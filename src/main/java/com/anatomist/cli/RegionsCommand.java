package com.anatomist.cli;

import com.anatomist.query.BranchSlice;
import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Command(name="regions", mixinStandardHelpOptions=true,
        description="Return indexed control regions owned by a callable.",
        footer="%nAccepts: entity(callable)%nEmits: control_region + evidence%n%nExample:%n  anatomist resolve 'p.Type#run()' --kind callable --unique | anatomist regions | anatomist sites-in")
public final class RegionsCommand extends SemanticCommand {
    @Option(names="--kind", defaultValue="branch") String kind;
    @Option(names="--limit", defaultValue="50") int limit;
    @Override protected Set<String> acceptedInputRecords(){return Set.of("entity");}
    @Override protected Result execute(QueryService query,SemanticIdentity identity,SemanticStreamWriter writer){
        kind=CliValidation.choice("--kind",kind,"branch","loop","all");CliValidation.positive("--limit",limit);
        AtomicInteger seeds=new AtomicInteger(),emitted=new AtomicInteger();boolean[] complete={true},truncated={false};
        SemanticStreamReader.Summary input=SemanticStreamReader.readFrames(System.in,Set.of("entity"),acceptUnframed,identity,frame->{seeds.incrementAndGet();int count=0;boolean frameComplete=frame.evidence().complete();boolean frameTruncated=false;for(SemanticRecord record:frame.records()){SemanticRecord.Entity entity=(SemanticRecord.Entity)record;if(!"callable".equals(entity.kind()))throw new IllegalArgumentException("regions accepts callable entities; got "+entity.kind());for(BranchSlice row:query.branchesOf(entity.id(),1,false,null)){boolean loop=row.branchKind!=null&&Set.of("for","foreach","while","do").contains(row.branchKind);if(("loop".equals(kind)&&!loop)||("branch".equals(kind)&&loop))continue;if(count>=limit){truncated[0]=true;frameTruncated=true;frameComplete=false;break;}String stable=row.owner+"\n"+row.context;String id="region:sha256:"+SemanticIdentity.sha256(stable);String child=SemanticRecords.childSeed(frame.seedId(),"regions",id);Map<String,Object> out=SemanticRecords.common("control_region",child,frame.seedId(),identity);out.put("id",id);out.put("caller",row.owner);out.put("kind",loop?"loop":"branch");out.put("mechanism","java."+(row.branchKind==null?"control":row.branchKind));out.put("context",row.context);out.put("producer_id","java-core");out.put("origin","extracted");out.put("resolution_status","exact");if(row.sourceFile!=null){Map<String,Object> source=new LinkedHashMap<>();source.put("file",row.sourceFile);if(row.branchLine!=null)source.put("start_line",row.branchLine);out.put("source",source);}out.putAll(SemanticRecords.lineage(entity.raw()));writer.write(out);writer.write(SemanticRecords.seedEvidence(child,frame.seedId(),1,true,null,identity));count++;emitted.incrementAndGet();}}writer.write(SemanticRecords.seedEvidence(frame.seedId(),null,count,frameComplete,frameComplete?null:frameTruncated?"QUERY_LIMIT_TRUNCATED":"UPSTREAM_INCOMPLETE",frameTruncated,identity));if(!frameComplete)complete[0]=false;});
        if(seeds.get()==0)throw new IllegalArgumentException("regions requires an entity stream on stdin");return new Result(seeds.get(),emitted.get(),input.complete()&&complete[0],truncated[0]);
    }
}
