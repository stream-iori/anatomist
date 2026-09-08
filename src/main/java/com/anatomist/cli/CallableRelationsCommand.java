package com.anatomist.cli;

import com.anatomist.query.JavaSemanticRows.CallableRelation;
import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Command(modelTransformer = AgentHelp.class, name="callable-relations", mixinStandardHelpOptions=true,
        description = "Find overridden contracts or overriding methods.",
        footer = "%nBoundary: Method override and interface-contract facts, not call sites.%n%nExample:%n  anatomist pipeline -- resolve 'p.Api#run()' --kind callable --exact --unique --then callable-relations --direction incoming")
public final class CallableRelationsCommand extends TransformSemanticCommand {
    @Option(names="--direction", defaultValue="outgoing", description="outgoing: overridden methods/contracts; incoming: overriding methods (default outgoing).") String direction;
    @Option(names="--transitive", description="Include indirect overrides/contracts (default false).") boolean transitive;
    @Option(names="--max-depth", defaultValue="20", description="Maximum override traversal depth; >=1 (default 20).") int maxDepth;
    @Option(names="--limit", defaultValue="50", description="Maximum relations per entity; >=1 (default 50).") int limit;
    @Override protected Set<String> acceptedInputRecords() { return Set.of("entity"); }
    @Override protected Result execute(QueryService query, SemanticIdentity identity, SemanticStreamWriter writer) {
        direction = CliValidation.choice("--direction", direction, "outgoing", "incoming");
        CliValidation.positive("--max-depth", maxDepth); CliValidation.positive("--limit", limit);
        AtomicInteger seeds = new AtomicInteger(), emitted = new AtomicInteger(); AtomicBoolean complete = new AtomicBoolean(true);
        SemanticStreamReader.Summary input = readFrames(Set.of("entity"), acceptUnframed, identity, frame -> {
            seeds.incrementAndGet(); int count=0; boolean frameComplete=frame.evidence().complete();
            for (SemanticRecord record: frame.records()) {
                SemanticRecord.Entity entity=(SemanticRecord.Entity) record;
                if (!"callable".equals(entity.kind())) throw new IllegalArgumentException("callable-relations accepts callable entities; got "+entity.kind());
                var rows=query.callableRelations(entity.id(), direction, transitive, maxDepth, limit);
                for (CallableRelation row: rows) {
                    String child=SemanticRecords.childSeed(frame.seedId(), "callable-relations", row.id());
                    writer.write(JavaSemanticRecordMaps.callableRelation(row, child, frame.seedId(), entity.raw(), identity));
                    writer.write(SemanticRecords.seedEvidence(child, frame.seedId(), 1, true, null, identity)); count++; emitted.incrementAndGet();
                    if (row.externalObject() || !"exact".equals(row.resolutionStatus())) frameComplete=false;
                }
                if (rows.size()>=limit) frameComplete=false;
            }
            writer.write(SemanticRecords.seedEvidence(frame.seedId(), null, count, frameComplete, frameComplete?null:"OPEN_WORLD_OR_HEURISTIC", identity));
            if(!frameComplete) complete.set(false);
        });
        if(seeds.get()==0) throw new IllegalArgumentException("callable-relations requires an entity stream on stdin");
        if(!input.complete()) complete.set(false);
        return new Result(seeds.get(), emitted.get(), complete.get(), false);
    }
}
