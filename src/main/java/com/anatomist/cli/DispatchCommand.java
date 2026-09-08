package com.anatomist.cli;

import com.anatomist.query.JavaSemanticRows.DispatchTarget;
import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Command(modelTransformer = AgentHelp.class, name="dispatch", mixinStandardHelpOptions=true,
        description = "Expand a call site into possible virtual targets.",
        footer = "%nBoundary: Candidates are static possibilities. Source reads the recorded call location; resolve a target separately for its body.%n%nExample:%n  anatomist pipeline -- resolve 'p.Service#run()' --kind callable --exact --unique --then calls --then dispatch")
public final class DispatchCommand extends TransformSemanticCommand {
    @Option(names="--algorithm", defaultValue="auto", description="auto: exact for nonvirtual calls, hierarchy candidates otherwise; exact: resolved targets only; cha: hierarchy candidates (default auto).") String algorithm;
    @Option(names="--world", defaultValue="workspace-open", description="Evidence scope: workspace-open | workspace-closed | classpath-open (default workspace-open). Closed assumes the workspace is exhaustive.") String world;
    @Option(names="--max-depth", defaultValue="20", description="Maximum inheritance traversal depth; >=1 (default 20).") int maxDepth;
    @Option(names="--limit", defaultValue="50", description="Maximum targets per call site; >=1 (default 50).") int limit;
    @Override protected Set<String> acceptedInputRecords(){ return Set.of("call_site"); }
    @Override protected Result execute(QueryService query, SemanticIdentity identity, SemanticStreamWriter writer){
        algorithm=CliValidation.choice("--algorithm", algorithm, "auto", "exact", "cha");
        world=CliValidation.choice("--world", world, "workspace-open", "workspace-closed", "classpath-open");
        CliValidation.positive("--max-depth", maxDepth); CliValidation.positive("--limit", limit);
        AtomicInteger seeds=new AtomicInteger(), emitted=new AtomicInteger(); AtomicBoolean complete=new AtomicBoolean(true), truncated=new AtomicBoolean(false);
        SemanticStreamReader.Summary input=readFrames(Set.of("call_site"), acceptUnframed, identity, frame->{
            seeds.incrementAndGet(); int count=0; boolean frameComplete=frame.evidence().complete(),frameTruncated=false;
            var reasons=new java.util.TreeSet<String>();
            for(SemanticRecord record:frame.records()){
                SemanticRecord.CallSite site=(SemanticRecord.CallSite)record;
                var result=query.dispatchDetailed(site.raw(), algorithm, world, maxDepth, limit,100_000);
                reasons.addAll(result.reasons());frameComplete &= result.complete();frameTruncated |= result.truncated();
                for(DispatchTarget row:result.targets()){
                    String child=SemanticRecords.childSeed(frame.seedId(), "dispatch", row.id());
                    writer.write(JavaSemanticRecordMaps.dispatch(row, child, frame.seedId(), site.raw(), identity));
                    writer.write(SemanticRecords.seedEvidence(child, frame.seedId(), 1, true, null, identity)); count++; emitted.incrementAndGet();
                }
            }
            if(frameTruncated) truncated.set(true);
            var evidence=SemanticRecords.seedEvidence(frame.seedId(), null, count, frameComplete,
                    frameTruncated?"QUERY_LIMIT_TRUNCATED":frameComplete?null:"OPEN_WORLD_OR_HEURISTIC", frameTruncated, identity);
            evidence.put("dispatch_reasons",java.util.List.copyOf(reasons));writer.write(evidence);
            if(!frameComplete) complete.set(false);
        });
        if(seeds.get()==0) throw new IllegalArgumentException("dispatch requires a call_site stream on stdin");
        if(!input.complete()) complete.set(false);
        return new Result(seeds.get(), emitted.get(), complete.get(), truncated.get());
    }
}
