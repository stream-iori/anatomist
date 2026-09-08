package com.anatomist.cli;

import com.anatomist.query.JavaSemanticRows.RuntimeImplementation;
import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Command(modelTransformer = AgentHelp.class, name="runtime-implementations", mixinStandardHelpOptions=true,
        description = "Find statically instantiable implementation candidates.",
        footer = "%nBoundary: Static candidates, not live objects. Open-world evidence may remain partial.%n%nExample:%n  anatomist pipeline -- resolve p.Api --kind type --unique --then runtime-implementations")
public final class RuntimeImplementationsCommand extends TransformSemanticCommand {
    @Option(names="--instantiability", defaultValue="yes", description="Candidate instantiability: yes | unknown | all (default yes).") String instantiability;
    @Option(names="--world", defaultValue="workspace-open", description="Evidence scope: workspace-open | workspace-closed | classpath-open (default workspace-open). Closed assumes the workspace is exhaustive.") String world;
    @Option(names="--max-depth", defaultValue="20", description="Maximum inheritance traversal depth; >=1 (default 20).") int maxDepth;
    @Option(names="--limit", defaultValue="50", description="Maximum candidates per entity; >=1 (default 50).") int limit;
    @Override protected Set<String> acceptedInputRecords() { return Set.of("entity"); }

    @Override protected Result execute(QueryService query, SemanticIdentity identity, SemanticStreamWriter writer) {
        instantiability = CliValidation.choice("--instantiability", instantiability, "yes", "unknown", "all");
        world = CliValidation.choice("--world", world, "workspace-open", "workspace-closed", "classpath-open");
        CliValidation.positive("--max-depth", maxDepth); CliValidation.positive("--limit", limit);
        AtomicInteger seeds = new AtomicInteger(), emitted = new AtomicInteger();
        SemanticStreamReader.Summary input = readFrames(Set.of("entity"), acceptUnframed, identity, frame -> {
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
