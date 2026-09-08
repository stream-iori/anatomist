package com.anatomist.cli;

import com.anatomist.query.NodeRow;
import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Command(modelTransformer = AgentHelp.class, name="members", mixinStandardHelpOptions=true,
        description = "List members of a type or configuration container.",
        footer = "%nBoundary: Containment only; use type-relations for inheritance.%n%nExample:%n  anatomist pipeline -- resolve p.Service --kind type --unique --then members")
public final class MembersCommand extends TransformSemanticCommand {
    @Option(names="--recursive", description="Include nested members (default false).") boolean recursive;
    @Option(names="--kind", description="Filter one semantic entity kind, e.g. type, callable or value.") String kind;
    @Option(names="--max-depth", defaultValue="20", description="Containment depth with --recursive; >=1 (default 20).") int maxDepth;
    @Option(names="--limit", defaultValue="50", description="Maximum members per seed; >=1 (default 50).") int limit;
    @Override protected Set<String> acceptedInputRecords(){ return Set.of("entity"); }
    @Override protected Result execute(QueryService query, SemanticIdentity identity, SemanticStreamWriter writer){
        CliValidation.positive("--max-depth", maxDepth); CliValidation.positive("--limit", limit);
        AtomicInteger seeds=new AtomicInteger(), emitted=new AtomicInteger(); boolean[] allComplete={true}; boolean[] anyTruncated={false};
        SemanticStreamReader.Summary input=readFrames(Set.of("entity"), acceptUnframed, identity, frame->{
            seeds.incrementAndGet(); int count=0; boolean frameComplete=frame.evidence().complete(); boolean frameTruncated=false;
            for(SemanticRecord record:frame.records()){
                SemanticRecord.Entity entity=(SemanticRecord.Entity)record;
                var rows=query.semanticMembers(entity.id(), recursive, maxDepth, limit+1);
                for(NodeRow row:rows){
                    if(kind!=null && !kind.equals(SemanticRecords.kind(row.kind))) continue;
                    if(count>=limit){ frameComplete=false; frameTruncated=true; anyTruncated[0]=true; break; }
                    String child=SemanticRecords.childSeed(frame.seedId(), "members", row.id);
                    Map<String,Object> out=SemanticRecords.entity(row, child, "exact", identity);
                    out.put("parent_seed_id", frame.seedId()); out.putAll(SemanticRecords.lineage(entity.raw()));
                    writer.write(out); writer.write(SemanticRecords.seedEvidence(child, frame.seedId(), 1, true, null, identity)); count++; emitted.incrementAndGet();
                }
            }
            writer.write(SemanticRecords.seedEvidence(frame.seedId(), null, count, frameComplete,
                    frameComplete?null:frameTruncated?"QUERY_LIMIT_TRUNCATED":"UPSTREAM_INCOMPLETE", frameTruncated, identity)); if(!frameComplete) allComplete[0]=false;
        });
        if(seeds.get()==0) throw new IllegalArgumentException("members requires an entity stream on stdin");
        return new Result(seeds.get(), emitted.get(), input.complete()&&allComplete[0], anyTruncated[0]);
    }
}
