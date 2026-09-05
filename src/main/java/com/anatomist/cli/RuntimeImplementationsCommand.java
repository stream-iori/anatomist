package com.anatomist.cli;

import com.anatomist.query.JavaSemanticRows.RuntimeImplementation;
import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Command(name="runtime-implementations", mixinStandardHelpOptions=true,
        description="Derive instantiable Java implementations with an explicit proof and world assumption.",
        footer="%nAccepts: entity(type)%nEmits: entity + evidence%nCapability: java-type-semantics%n%nExample:%n  anatomist resolve p.Api --kind type --exact --unique | anatomist runtime-implementations --instantiability yes")
public final class RuntimeImplementationsCommand extends SemanticCommand {
    @Option(names="--instantiability", defaultValue="yes") String instantiability;
    @Option(names="--world", defaultValue="workspace-open") String world;
    @Option(names="--max-depth", defaultValue="20") int maxDepth;
    @Option(names="--limit", defaultValue="50") int limit;
    @Override protected SemanticCapabilityRegistry.Capability requiredCapability() { return SemanticCapabilityRegistry.Capability.TYPE_SEMANTICS; }
    @Override protected Set<String> acceptedInputRecords() { return Set.of("entity"); }

    @Override protected Result execute(QueryService query, SemanticIdentity identity, SemanticStreamWriter writer) {
        instantiability = CliValidation.choice("--instantiability", instantiability, "yes", "unknown", "all");
        world = CliValidation.choice("--world", world, "workspace-open", "workspace-closed", "classpath-open");
        CliValidation.positive("--max-depth", maxDepth); CliValidation.positive("--limit", limit);
        AtomicInteger seeds = new AtomicInteger(), emitted = new AtomicInteger();
        SemanticStreamReader.Summary input = SemanticStreamReader.readFrames(System.in, Set.of("entity"), acceptUnframed, identity, frame -> {
            seeds.incrementAndGet(); int count = 0;
            for (SemanticRecord record : frame.records()) {
                SemanticRecord.Entity entity = (SemanticRecord.Entity) record;
                if (!"type".equals(entity.kind())) throw new IllegalArgumentException("runtime-implementations accepts type entities; got " + entity.kind());
                for (RuntimeImplementation row : query.runtimeImplementations(entity.id(), instantiability, world, maxDepth, limit)) {
                    String child = SemanticRecords.childSeed(frame.seedId(), "runtime-implementations", row.entity().id);
                    Map<String,Object> out = SemanticRecords.entity(row.entity(), child, "heuristic", identity);
                    out.put("parent_seed_id", frame.seedId()); out.put("instantiability", row.instantiability());
                    out.put("reason", row.reason()); out.put("world", row.world());
                    out.put("proof", JavaSemanticRecordMaps.proof(row.proof())); out.putAll(SemanticRecords.lineage(entity.raw()));
                    writer.write(out); writer.write(SemanticRecords.seedEvidence(child, frame.seedId(), 1, true, null, identity));
                    count++; emitted.incrementAndGet();
                }
            }
            boolean frameComplete = frame.evidence().complete() && "workspace-closed".equals(world);
            writer.write(SemanticRecords.seedEvidence(frame.seedId(), null, count, frameComplete,
                    frameComplete ? null : "OPEN_WORLD", identity));
        });
        if (seeds.get() == 0) throw new IllegalArgumentException("runtime-implementations requires an entity stream on stdin");
        boolean complete = input.complete() && "workspace-closed".equals(world);
        return new Result(seeds.get(), emitted.get(), complete, false);
    }
}
