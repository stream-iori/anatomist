package com.anatomist.cli;

import com.anatomist.model.GraphConstants;
import com.anatomist.query.GenericSemanticRows;
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
public final class RegionsCommand extends TransformSemanticCommand {
    @Option(names="--kind", defaultValue="branch") String kind;
    @Option(names="--limit", defaultValue="50") int limit;
    @Override protected Set<String> acceptedInputRecords(){return Set.of("entity");}
    @Override protected Result execute(QueryService query,SemanticIdentity identity,SemanticStreamWriter writer){
        kind=CliValidation.choice("--kind",kind,"branch","loop","all");CliValidation.positive("--limit",limit);
        AtomicInteger seeds=new AtomicInteger(),emitted=new AtomicInteger();boolean[] complete={true},truncated={false};
        SemanticStreamReader.Summary input=readFrames(Set.of("entity"),acceptUnframed,identity,frame->{seeds.incrementAndGet();int count=0;boolean frameComplete=frame.evidence().complete();boolean frameTruncated=false;for(SemanticRecord record:frame.records()){SemanticRecord.Entity entity=(SemanticRecord.Entity)record;if(!"callable".equals(entity.kind()))throw new IllegalArgumentException("regions accepts callable entities; got "+entity.kind());Map<String,GenericSemanticRows.Site> regions=new LinkedHashMap<>();for(GenericSemanticRows.Site site:query.semanticSites(entity.id(),"outgoing",Set.of(GraphConstants.Relation.CALLS,GraphConstants.Relation.READS,GraphConstants.Relation.WRITES),SemanticRecords.MAX_LIMIT)){if(site.context()!=null&&!site.context().isBlank())regions.putIfAbsent(site.context(),site);}for(var entry:regions.entrySet()){String context=entry.getKey();GenericSemanticRows.Site site=entry.getValue();String mechanism=lastKind(context);boolean loop=Set.of("for","foreach","while","do").contains(mechanism);if(("loop".equals(kind)&&!loop)||("branch".equals(kind)&&loop))continue;if(count>=limit){truncated[0]=true;frameTruncated=true;frameComplete=false;break;}String stable=entity.id()+"\n"+context;String id="region:sha256:"+SemanticIdentity.sha256(stable);String child=SemanticRecords.childSeed(frame.seedId(),"regions",id);Map<String,Object> out=SemanticRecords.common("control_region",child,frame.seedId(),identity);out.put("id",id);out.put("caller",entity.id());out.put("kind",loop?"loop":"branch");out.put("mechanism","java."+mechanism);out.put("context",context);out.put("producer_id",site.producerId());out.put("origin","extracted");out.put("resolution_status","exact");if(site.sourceFile()!=null){Map<String,Object> source=new LinkedHashMap<>();source.put("file",site.sourceFile());Integer line=contextLine(context);if(line!=null)source.put("start_line",line);out.put("source",source);}out.putAll(SemanticRecords.lineage(entity.raw()));writer.write(out);writer.write(SemanticRecords.seedEvidence(child,frame.seedId(),1,true,null,identity));count++;emitted.incrementAndGet();}}writer.write(SemanticRecords.seedEvidence(frame.seedId(),null,count,frameComplete,frameComplete?null:frameTruncated?"QUERY_LIMIT_TRUNCATED":"UPSTREAM_INCOMPLETE",frameTruncated,identity));if(!frameComplete)complete[0]=false;});
        if(seeds.get()==0)throw new IllegalArgumentException("regions requires an entity stream on stdin");return new Result(seeds.get(),emitted.get(),input.complete()&&complete[0],truncated[0]);
    }

    private static String lastKind(String context){String segment=context.substring(context.lastIndexOf('>')+1);int at=segment.indexOf('@');return at<0?segment:segment.substring(0,at);}
    private static Integer contextLine(String context){String segment=context.substring(context.lastIndexOf('>')+1);int marker=segment.indexOf("@L");if(marker<0)return null;try{return Integer.valueOf(segment.substring(marker+2));}catch(NumberFormatException ignored){return null;}}
}
