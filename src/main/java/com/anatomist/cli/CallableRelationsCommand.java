package com.anatomist.cli;

import com.anatomist.query.JavaSemanticRows.CallableRelation;
import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Command(name="callable-relations", mixinStandardHelpOptions=true,
        description="Return override and interface-contract callable facts.",
        footer="%nAccepts: entity(callable)%nEmits: callable_relation + evidence%nOperation: callable-relations; inspect with: anatomist operations callable-relations")
public final class CallableRelationsCommand extends TransformSemanticCommand {
    @Option(names="--direction", defaultValue="outgoing") String direction;
    @Option(names="--transitive") boolean transitive;
    @Option(names="--max-depth", defaultValue="20") int maxDepth;
    @Option(names="--limit", defaultValue="50") int limit;
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
