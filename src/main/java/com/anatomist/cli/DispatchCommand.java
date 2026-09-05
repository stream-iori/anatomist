package com.anatomist.cli;

import com.anatomist.query.JavaSemanticRows.DispatchTarget;
import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Command(name="dispatch", mixinStandardHelpOptions=true,
        description="Expand static call targets into possible Java dispatch candidates.",
        footer="%nAccepts: call_site%nEmits: dispatch_target + evidence%nOperation: dispatch; inspect with: anatomist operations dispatch%nNote: candidates are static possibilities, not observed runtime calls.%n%nExample:%n  anatomist resolve 'p.Service#run()' --kind callable --exact --unique | anatomist calls | anatomist dispatch")
public final class DispatchCommand extends SemanticCommand {
    @Option(names="--algorithm", defaultValue="auto") String algorithm;
    @Option(names="--world", defaultValue="workspace-open") String world;
    @Option(names="--max-depth", defaultValue="20") int maxDepth;
    @Option(names="--limit", defaultValue="50") int limit;
    @Override protected Set<String> acceptedInputRecords(){ return Set.of("call_site"); }
    @Override protected Result execute(QueryService query, SemanticIdentity identity, SemanticStreamWriter writer){
        algorithm=CliValidation.choice("--algorithm", algorithm, "auto", "exact", "cha");
        world=CliValidation.choice("--world", world, "workspace-open", "workspace-closed", "classpath-open");
        CliValidation.positive("--max-depth", maxDepth); CliValidation.positive("--limit", limit);
        AtomicInteger seeds=new AtomicInteger(), emitted=new AtomicInteger(); AtomicBoolean complete=new AtomicBoolean(true), truncated=new AtomicBoolean(false);
        SemanticStreamReader.Summary input=readFrames(Set.of("call_site"), acceptUnframed, identity, frame->{
            seeds.incrementAndGet(); int count=0; boolean frameComplete=frame.evidence().complete();
            for(SemanticRecord record:frame.records()){
                SemanticRecord.CallSite site=(SemanticRecord.CallSite)record;
                var rows=query.dispatch(site.raw(), algorithm, world, maxDepth, limit);
                for(DispatchTarget row:rows){
                    String child=SemanticRecords.childSeed(frame.seedId(), "dispatch", row.id());
                    writer.write(JavaSemanticRecordMaps.dispatch(row, child, frame.seedId(), site.raw(), identity));
                    writer.write(SemanticRecords.seedEvidence(child, frame.seedId(), 1, true, null, identity)); count++; emitted.incrementAndGet();
                    if("possible".equals(row.candidateKind()) || !"exact".equals(row.resolutionStatus())) frameComplete=false;
                }
                if(rows.size()>=limit){ truncated.set(true); frameComplete=false; }
            }
            if(!"workspace-closed".equals(world)) frameComplete=false;
            writer.write(SemanticRecords.seedEvidence(frame.seedId(), null, count, frameComplete,
                    truncated.get()?"QUERY_LIMIT_TRUNCATED":frameComplete?null:"OPEN_WORLD_OR_HEURISTIC", truncated.get(), identity));
            if(!frameComplete) complete.set(false);
        });
        if(seeds.get()==0) throw new IllegalArgumentException("dispatch requires a call_site stream on stdin");
        if(!input.complete()) complete.set(false);
        return new Result(seeds.get(), emitted.get(), complete.get(), truncated.get());
    }
}
