package com.anatomist.cli;

import com.anatomist.query.CallSiteRow;
import com.anatomist.query.QueryCoverageService;
import com.anatomist.query.QueryEvidence;
import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.SemanticIdentity;
import com.anatomist.query.semantic.SemanticRecord;
import com.anatomist.query.semantic.SemanticRecords;
import com.anatomist.query.semantic.SemanticProviders;
import com.anatomist.query.semantic.SemanticStreamReader;
import com.anatomist.query.semantic.SemanticStreamWriter;
import com.anatomist.query.semantic.SemanticCursor;
import com.anatomist.query.semantic.RelationshipIdentity;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Command(name = "calls", mixinStandardHelpOptions = true,
        description = "Return direct source call sites; dispatch/override expansion is not included.",
        footer = "%nAccepts: callable entity%nEmits: call_site + evidence%nOperation: calls; inspect with: anatomist operations calls%nDefault limits: 50 sites/entity; hard max 100000%n%nExample:%n  anatomist resolve 'p.Service#run()' --kind callable --exact --unique | anatomist calls --direction outgoing")
public final class CallsCommand extends SemanticCommand {
    @Option(names = "--direction", defaultValue = "outgoing",
            description = "Direction: outgoing | incoming (default outgoing).")
    String direction;

    @Option(names = "--limit", defaultValue = "50",
            description = "Maximum call sites per entity (default 50, max 100000).")
    int limit;

    @Override protected Set<String> acceptedInputRecords() { return Set.of("entity"); }

    @Override
    protected Result execute(QueryService query, SemanticIdentity identity,
                             SemanticStreamWriter writer) {
        direction = CliValidation.choice("--direction", direction, "outgoing", "incoming");
        CliValidation.positive("--limit", limit);
        if (limit > SemanticRecords.MAX_LIMIT) throw new IllegalArgumentException(
                "--limit must be <= " + SemanticRecords.MAX_LIMIT);
        AtomicInteger seeds = new AtomicInteger();
        AtomicInteger emitted = new AtomicInteger();
        AtomicBoolean complete = new AtomicBoolean(true);
        AtomicBoolean truncated = new AtomicBoolean(false);
        SemanticStreamReader.Summary input = readFrames(
                Set.of("entity"), acceptUnframed, identity, frame -> {
            seeds.incrementAndGet();
            if (frame.records().isEmpty()) {
                writer.write(SemanticRecords.seedEvidence(frame.seedId(), null, 0,
                        frame.evidence().complete(), frame.evidence().complete()
                                ? null : "UPSTREAM_INCOMPLETE", identity));
                if (!frame.evidence().complete()) complete.set(false);
                return;
            }
            String parent = frame.seedId();
            boolean limited = false;
            boolean frameComplete = frame.evidence().complete();
            String evidenceCode = frameComplete ? null : "UPSTREAM_INCOMPLETE";
            int count = 0;
            for (SemanticRecord record : frame.records()) {
                    SemanticRecord.Entity entity = (SemanticRecord.Entity) record;
                    if (!"callable".equals(entity.kind())) {
                        throw new IllegalArgumentException("calls accepts callable entities; got "
                                + entity.kind());
                    }
                    String id = entity.id();
                    List<CallSiteRow> buffered = new ArrayList<>();
                    try (SemanticCursor<CallSiteRow> cursor = query.callSitesCursor(id, direction)) {
                        while (cursor.hasNext()) {
                            buffered.add(cursor.next());
                            if (buffered.size() > limit) break;
                        }
                    }
                    int entityCount = Math.min(buffered.size(), limit);
                    limited |= buffered.size() > limit;
                    for (int item = 0; item < entityCount; item++) {
                        CallSiteRow site = buffered.get(item);
                        count++;
                        String child = SemanticRecords.childSeed(parent, "calls", site.id);
                        writer.write(callSite(site, child, parent, entity.raw(), identity));
                        writer.write(SemanticRecords.seedEvidence(child, parent, 1, true,
                                null, identity));
                        emitted.incrementAndGet();
                    }
                    QueryEvidence coverage = new QueryCoverageService(query.connection()).assess(
                            "outgoing".equals(direction)
                                    ? QueryCoverageService.Capability.CALL_OUTGOING
                                    : QueryCoverageService.Capability.CALL_INCOMING,
                            List.of(id), module, scope, entityCount > 0, false);
                    boolean parentComplete = !limited && "complete".equals(coverage.coverage());
                    frameComplete &= parentComplete;
                    if (limited) evidenceCode = "QUERY_LIMIT_TRUNCATED";
                    else if (coverage.code() != null) evidenceCode = coverage.code();
            }
            writer.write(SemanticRecords.seedEvidence(parent, null, count, frameComplete,
                    evidenceCode, limited, identity));
            if (limited) truncated.set(true);
            if (!frameComplete) complete.set(false);
        });
        if (seeds.get() == 0) throw new IllegalArgumentException(
                "calls requires a callable entity stream on stdin");
        if (!input.complete()) complete.set(false);
        return new Result(seeds.get(), emitted.get(), complete.get(), truncated.get());
    }

    private static Map<String, Object> callSite(CallSiteRow site,
                                                 String seed, String parent,
                                                 Map<String, Object> input,
                                                 SemanticIdentity identity) {
        Map<String, Object> out = SemanticRecords.common("call_site", seed, parent, identity);
        out.put("id", site.id);
        String language = SemanticProviders.languageForProducer(site.producerId);
        if (language != null) out.put("language", language);
        out.put("caller", site.callerId);
        if (site.context != null) out.put("context", site.context);
        if (site.syntaxTarget != null) out.put("syntax_target", site.syntaxTarget);
        if (site.receiverStaticType != null) out.put("receiver_static_type", site.receiverStaticType);
        List<Map<String, Object>> targets = site.targets.stream().map(CallsCommand::target).toList();
        out.put("resolved_targets", targets);
        if (targets.size() == 1) out.put("resolved_target", targets.getFirst().get("id"));
        out.put("dispatch_kind", site.dispatchKind == null ? "unknown"
                : site.dispatchKind.toLowerCase(java.util.Locale.ROOT));
        String provider = SemanticProviders.providerForProducer(site.producerId);
        List<String> targetIds = site.targets.stream().map(CallSiteRow.Target::id)
                .filter(java.util.Objects::nonNull).distinct().sorted().toList();
        Map<String, Object> relationship = RelationshipIdentity.fields(
                "provider", provider, "language", language, "caller", site.callerId,
                "dispatch_kind", site.dispatchKind == null ? "unknown"
                        : site.dispatchKind.toLowerCase(java.util.Locale.ROOT),
                "targets", targetIds);
        if (targetIds.isEmpty()) {
            relationship.put("syntax_target", site.syntaxTarget);
            relationship.put("receiver_static_type", site.receiverStaticType);
        }
        out.put("relationship_id", RelationshipIdentity.of("call_site", relationship));
        if (site.metadata != null) {
            Object metadata = com.anatomist.json.Json.parseTree(site.metadata);
            if (metadata instanceof Map<?, ?> values && values.get("lombok_usage") != null) {
                out.put("lombok_usage", values.get("lombok_usage"));
            }
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("file", site.sourceFile);
        source.put("start_line", site.beginLine);
        source.put("start_column", site.beginColumn);
        source.put("end_line", site.endLine);
        source.put("end_column", site.endColumn);
        source.put("ordinal", site.ordinal);
        out.put("source", source);
        out.put("producer_id", site.producerId);
        out.put("origin", site.origin);
        out.put("resolution_status", site.resolutionStatus);
        out.putAll(SemanticRecords.lineage(input));
        return out;
    }

    private static Map<String, Object> target(CallSiteRow.Target value) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("id", value.id());
        if (value.qualifiedName() != null) target.put("qualified_name", value.qualifiedName());
        target.put("external", value.external());
        target.put("resolution_status", value.resolutionStatus());
        if (value.confidence() != null) target.put("confidence", value.confidence().toLowerCase());
        if (value.producerId() != null) target.put("producer_id", value.producerId());
        return target;
    }
}
