package com.anatomist.cli;

import com.anatomist.query.JavaSemanticRows.TypeRelation;
import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Command(name = "type-relations", mixinStandardHelpOptions = true,
        description = "Return Java type facts without mixing subtype and conformance closure.",
        footer = "%nAccepts: entity(type)%nEmits: type_relation + evidence%nOperation: type-relations; inspect with: anatomist operations type-relations%n%nExample:%n  anatomist resolve p.Api --kind type --exact --unique | anatomist type-relations --direction incoming")
public final class TypeRelationsCommand extends TransformSemanticCommand {
    @Option(names="--direction", defaultValue="outgoing") String direction;
    @Option(names="--semantic", defaultValue="any") String semantic;
    @Option(names="--transitive") boolean transitive;
    @Option(names="--max-depth", defaultValue="20") int maxDepth;
    @Option(names="--limit", defaultValue="50") int limit;

    @Override protected Set<String> acceptedInputRecords() { return Set.of("entity"); }

    @Override protected Result execute(QueryService query, SemanticIdentity identity,
                                       SemanticStreamWriter writer) {
        direction = CliValidation.choice("--direction", direction, "outgoing", "incoming");
        semantic = CliValidation.choice("--semantic", semantic, "any", "subtype-of", "conforms-to");
        CliValidation.positive("--max-depth", maxDepth);
        CliValidation.positive("--limit", limit);
        if (limit > SemanticRecords.MAX_LIMIT) throw new IllegalArgumentException("--limit must be <= " + SemanticRecords.MAX_LIMIT);
        AtomicInteger seeds = new AtomicInteger(), emitted = new AtomicInteger();
        AtomicBoolean complete = new AtomicBoolean(true), truncated = new AtomicBoolean(false);
        SemanticStreamReader.Summary input = readFrames(Set.of("entity"),
                acceptUnframed, identity, frame -> {
            seeds.incrementAndGet();
            int count = 0;
            boolean frameComplete = frame.evidence().complete();
            for (SemanticRecord record : frame.records()) {
                SemanticRecord.Entity entity = (SemanticRecord.Entity) record;
                if (!"type".equals(entity.kind())) throw new IllegalArgumentException("type-relations accepts type entities; got " + entity.kind());
                List<TypeRelation> rows = query.typeRelations(entity.id(), direction, semantic,
                        transitive, maxDepth, limit);
                for (TypeRelation row : rows) {
                    String child = SemanticRecords.childSeed(frame.seedId(), "type-relations", row.id());
                    writer.write(JavaSemanticRecordMaps.typeRelation(row, child, frame.seedId(), entity.raw(), identity));
                    writer.write(SemanticRecords.seedEvidence(child, frame.seedId(), 1, true, null, identity));
                    count++; emitted.incrementAndGet();
                    if (row.externalObject() || !"exact".equals(row.resolutionStatus())) frameComplete = false;
                }
                if (rows.size() >= limit) { truncated.set(true); frameComplete = false; }
            }
            writer.write(SemanticRecords.seedEvidence(frame.seedId(), null, count, frameComplete,
                    truncated.get() ? "QUERY_LIMIT_TRUNCATED" : frameComplete ? null : "OPEN_WORLD_OR_HEURISTIC",
                    truncated.get(), identity));
            if (!frameComplete) complete.set(false);
        });
        if (seeds.get() == 0) throw new IllegalArgumentException("type-relations requires an entity stream on stdin");
        if (!input.complete()) complete.set(false);
        return new Result(seeds.get(), emitted.get(), complete.get(), truncated.get());
    }
}
