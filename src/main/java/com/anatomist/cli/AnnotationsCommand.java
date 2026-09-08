package com.anatomist.cli;

import com.anatomist.query.QueryService;
import com.anatomist.query.AnnotationRow;
import com.anatomist.query.semantic.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Command(modelTransformer = AgentHelp.class, name="annotations", mixinStandardHelpOptions=true,
        description = "Read direct or composed annotations on a declaration.",
        footer = "%nBoundary: Direct by default. Meta expansion does not rewrite @AliasFor attributes.%n%nExample:%n  anatomist pipeline -- resolve p.Service --kind type --unique --then annotations")
public final class AnnotationsCommand extends TransformSemanticCommand {
    @Option(names="--include-meta", description="Include cycle-safe meta-annotation closure (max depth 16); direct annotations remain marked direct=true.")
    boolean includeMeta;

    @Override protected Set<String> acceptedInputRecords(){return Set.of("entity");}
    @Override protected Result execute(QueryService query,SemanticIdentity identity,SemanticStreamWriter writer){AtomicInteger seeds=new AtomicInteger(),emitted=new AtomicInteger();SemanticStreamReader.Summary input=readFrames(Set.of("entity"),acceptUnframed,identity,frame->{seeds.incrementAndGet();int count=0;for(SemanticRecord record:frame.records()){SemanticRecord.Entity entity=(SemanticRecord.Entity)record;for(AnnotationRow annotation:query.annotations(entity.id(),includeMeta)){String stable=entity.id()+"\n"+annotation.name()+"\n"+annotation.targetPath()+"\n"+annotation.direct()+"\n"+annotation.metaDepth()+"\n"+annotation.via()+"\n"+annotation.attributes();String id="annotation:sha256:"+SemanticIdentity.sha256(stable);String child=SemanticRecords.childSeed(frame.seedId(),"annotations",id);Map<String,Object> out=SemanticRecords.common("annotation",child,frame.seedId(),identity);out.put("id",id);out.put("entity",entity.id());out.put("name",annotation.name());out.put("raw_name",annotation.rawName());if(annotation.attributes()!=null)out.put("attributes",annotation.attributes());out.put("target_kind",annotation.targetKind());if(annotation.targetPath()!=null)out.put("target_path",annotation.targetPath());out.put("language",annotation.language());out.put("mechanism",annotation.mechanism());out.put("direct",annotation.direct());out.put("meta_depth",annotation.metaDepth());out.put("via",via(annotation.via()));if(annotation.sourceFile()!=null)out.put("source_file",annotation.sourceFile());if(annotation.sourceLocation()!=null)out.put("source_location",annotation.sourceLocation());out.put("producer_id",annotation.producerId());out.put("origin","extracted");out.put("resolution_status",annotation.resolutionStatus());out.putAll(SemanticRecords.lineage(entity.raw()));writer.write(out);writer.write(SemanticRecords.seedEvidence(child,frame.seedId(),1,true,null,identity));count++;emitted.incrementAndGet();}}boolean complete=frame.evidence().complete();writer.write(SemanticRecords.seedEvidence(frame.seedId(),null,count,complete,complete?null:"UPSTREAM_INCOMPLETE",identity));});if(seeds.get()==0)throw new IllegalArgumentException("annotations requires an entity stream on stdin");return new Result(seeds.get(),emitted.get(),input.complete(),false);}

    private static List<String> via(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(">"))
                .filter(part -> !part.isBlank()).toList();
    }
}
