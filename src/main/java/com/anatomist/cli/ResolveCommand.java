package com.anatomist.cli;

import com.anatomist.query.NodeRow;
import com.anatomist.query.QueryService;
import com.anatomist.query.SymbolResolution;
import com.anatomist.query.semantic.SemanticIdentity;
import com.anatomist.query.semantic.SemanticRecord;
import com.anatomist.query.semantic.SemanticRecords;
import com.anatomist.query.semantic.SemanticStreamReader;
import com.anatomist.query.semantic.SemanticStreamWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Command(name = "resolve", mixinStandardHelpOptions = true,
        description = "Resolve exact selectors or entity candidates without fuzzy recall.",
        footer = "%nAccepts: entity_candidate | entity, or one CLI selector%nEmits: entity + evidence%nOperation: resolve; inspect with: anatomist operations resolve%nHard input limits: 1 MiB/line, 1000000 records/stream, 100000 records/seed, 100000 seeds%n%nExample:%n  anatomist search PaymentGateway --kind type --format ndjson | anatomist resolve --unique")
public final class ResolveCommand extends TransformSemanticCommand {
    @Parameters(index = "0", arity = "0..1", description = "Exact ID, FQN, or callable signature.")
    String selector;

    @Option(names = "--kind", defaultValue = "entity",
            description = "Selector kind: entity | type | callable | value.")
    String kind;

    @Option(names = "--exact", description = "Require exact-signature resolution.")
    boolean exact;

    @Option(names = "--unique", description = "Fail unless each seed resolves to one entity.")
    boolean unique;

    @Override protected Set<String> acceptedInputRecords() {
        return Set.of("entity_candidate", "entity");
    }

    @Override protected String directSeed() {
        return selector == null || selector.isBlank() ? null
                : SemanticRecords.rootSeed("resolve", selector);
    }

    @Override
    protected Result execute(QueryService query, SemanticIdentity identity,
                             SemanticStreamWriter writer) {
        kind = CliValidation.choice("--kind", kind, "entity", "type", "callable", "value");
        if (selector != null && !selector.isBlank()) {
            SymbolResolution resolution = resolve(query, selector, kind);
            List<NodeRow> candidates = checkedCandidates(resolution);
            String seed = SemanticRecords.rootSeed("resolve", selector);
            emit(seed, candidates, resolution.status(), true, writer, identity);
            return new Result(1, candidates.size(), true, false);
        }

        AtomicInteger seeds = new AtomicInteger();
        AtomicInteger emitted = new AtomicInteger();
        SemanticStreamReader.Summary input = readFrames(
                Set.of("entity_candidate", "entity"), acceptUnframed, identity, frame -> {
            seeds.incrementAndGet();
            if (frame.records().isEmpty()) {
                writer.write(SemanticRecords.seedEvidence(frame.seedId(), null, 0,
                        frame.evidence().complete(), frame.evidence().complete()
                                ? null : "UPSTREAM_INCOMPLETE", identity));
                return;
            }
            List<NodeRow> candidates = new ArrayList<>();
            for (SemanticRecord candidate : frame.records()) {
                String id = String.valueOf(candidate.raw().get("id"));
                SymbolResolution resolution = resolve(query, id,
                        canonicalKind(candidate, kind));
                candidates.addAll(resolution.candidates());
            }
            candidates = candidates.stream().collect(java.util.stream.Collectors.collectingAndThen(
                    java.util.stream.Collectors.toMap(row -> row.id, row -> row,
                            (left, right) -> left, LinkedHashMap::new),
                    map -> new ArrayList<>(map.values())));
            if (candidates.isEmpty()) throw new IllegalArgumentException(
                    "seed " + frame.seedId() + " did not resolve to an indexed entity");
            if (unique && candidates.size() != 1) throw new IllegalArgumentException(
                    "seed " + frame.seedId() + " resolves to " + candidates.size()
                            + " entities; use a full ID/signature or narrower search");
            emit(frame.seedId(), candidates,
                    candidates.size() == 1 ? SymbolResolution.Status.EXACT
                            : SymbolResolution.Status.AMBIGUOUS,
                    frame.evidence().complete(), writer, identity);
            emitted.addAndGet(candidates.size());
        });
        if (seeds.get() == 0) throw new IllegalArgumentException(
                "provide a selector or a semantic stream on stdin");
        return new Result(seeds.get(), emitted.get(), input.complete(), false);
    }

    private List<NodeRow> checkedCandidates(SymbolResolution resolution) {
        if (resolution.status() == SymbolResolution.Status.NOT_FOUND) resolution.requireUnique();
        if (exact && resolution.status() != SymbolResolution.Status.EXACT) resolution.requireExact();
        if (unique) return List.of(resolution.requireUnique());
        return resolution.candidates();
    }

    private void emit(String seed, List<NodeRow> candidates, SymbolResolution.Status status,
                      boolean complete, SemanticStreamWriter writer, SemanticIdentity identity) {
        String resolutionStatus = status == SymbolResolution.Status.EXACT && candidates.size() == 1
                ? "exact" : "ambiguous";
        for (NodeRow candidate : candidates) {
            writer.write(SemanticRecords.entity(candidate, seed, resolutionStatus, identity));
        }
        writer.write(SemanticRecords.seedEvidence(seed, null, candidates.size(), complete,
                complete ? null : "UNFRAMED_INPUT", identity));
    }

    private static String canonicalKind(SemanticRecord record, String requested) {
        return "entity".equals(requested) && record.raw().get("kind") != null
                ? String.valueOf(record.raw().get("kind")) : requested;
    }

    private static SymbolResolution resolve(QueryService query, String value, String targetKind) {
        return switch (targetKind) {
            case "type" -> query.resolveType(value);
            case "callable" -> query.resolveMethod(value);
            case "value" -> query.resolveField(value);
            default -> query.resolveNode(value);
        };
    }
}
