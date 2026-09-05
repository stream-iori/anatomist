package com.anatomist.cli;

import com.anatomist.query.QueryService;
import com.anatomist.query.SourceContext;
import com.anatomist.query.SourceRequest;
import com.anatomist.query.semantic.SemanticIdentity;
import com.anatomist.query.semantic.SemanticRecord;
import com.anatomist.query.semantic.SemanticRecords;
import com.anatomist.query.semantic.SemanticStreamReader;
import com.anatomist.query.semantic.SemanticStreamWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Command(name = "source", mixinStandardHelpOptions = true,
        description = "Project snapshot-verified source for an entity or source-backed site.",
        footer = "%nAccepts: entity | call_site | reference_site | access_site | control_region | dispatch_target%nEmits: source_slice + evidence%nOperation: source; inspect with: anatomist operations source%nDefault limits: 200 source lines; max 1000%n%nExample:%n  anatomist resolve 'p.Service#run()' --kind callable --exact --unique | anatomist source")
public final class SourceCommand extends SemanticCommand {
    @Option(names = "--limit", defaultValue = "200",
            description = "Source lines per subject (default 200, max 1000).")
    int limit;

    @Option(names = "--offset", defaultValue = "0",
            description = "Declaration-relative source line offset.")
    int offset;

    @Override protected Set<String> acceptedInputRecords() {
        return Set.of("entity", "declaration", "call_site", "reference_site", "access_site", "control_region", "dispatch_target");
    }

    @Override
    protected Result execute(QueryService query, SemanticIdentity identity,
                             SemanticStreamWriter writer) {
        SourceRequest request = new SourceRequest(limit, offset);
        AtomicInteger seeds = new AtomicInteger();
        AtomicInteger emitted = new AtomicInteger();
        AtomicBoolean complete = new AtomicBoolean(true);
        AtomicBoolean truncated = new AtomicBoolean(false);
        SemanticStreamReader.Summary input = readFrames(
                acceptedInputRecords(), acceptUnframed, identity,
                frame -> {
            seeds.incrementAndGet();
            if (frame.records().isEmpty()) {
                writer.write(SemanticRecords.seedEvidence(frame.seedId(), null, 0,
                        frame.evidence().complete(), frame.evidence().complete()
                                ? null : "UPSTREAM_INCOMPLETE", identity));
                if (!frame.evidence().complete()) complete.set(false);
                return;
            }
            String parent = frame.seedId();
            int frameEmitted = 0;
            boolean frameComplete = frame.evidence().complete();
            boolean frameTruncated = false;
            String evidenceCode = frameComplete ? null : "UPSTREAM_INCOMPLETE";
            for (SemanticRecord record : frame.records()) {
                    Map<String, Object> subject = record.raw();
                    SourceContext source = readSource(query, subject, request);
                    String stableSubject = String.valueOf(subject.get("id"));
                    String child = SemanticRecords.childSeed(parent, "source", stableSubject);
                    boolean ok = "ok".equals(source.status);
                    boolean fullyRead = ok && !Boolean.TRUE.equals(source.truncated);
                    if (ok) {
                        writer.write(sourceSlice(subject, source, child, parent, identity));
                        emitted.incrementAndGet();
                        frameEmitted++;
                        writer.write(SemanticRecords.seedEvidence(child, parent, 1, fullyRead,
                                fullyRead ? null : "SOURCE_PAGE_TRUNCATED",
                                !fullyRead, identity));
                    }
                    frameComplete &= fullyRead;
                    if (ok && !fullyRead) {
                        frameTruncated = true;
                        evidenceCode = "SOURCE_PAGE_TRUNCATED";
                    } else if (!ok) evidenceCode = source.warningCode;
            }
            writer.write(SemanticRecords.seedEvidence(parent, null, frameEmitted,
                    frameComplete, evidenceCode, frameTruncated, identity));
            if (frameTruncated) truncated.set(true);
            if (!frameComplete) complete.set(false);
        });
        if (seeds.get() == 0) throw new IllegalArgumentException(
                "source requires an entity or site stream on stdin");
        if (!input.complete()) complete.set(false);
        return new Result(seeds.get(), emitted.get(), complete.get(), truncated.get());
    }

    private static String sourceReference(Map<String, Object> subject) {
        String record = String.valueOf(subject.get("record"));
        Object value = "entity".equals(record) ? subject.get("id")
                : "declaration".equals(record) ? subject.get("entity") : subject.get("caller");
        if (value == null) throw new IllegalArgumentException(
                record + " record has no source-backed entity/caller");
        return String.valueOf(value);
    }

    private static SourceContext readSource(QueryService query, Map<String, Object> subject,
                                            SourceRequest request) {
        String record = String.valueOf(subject.get("record"));
        if ((record.endsWith("_site") || "dispatch_target".equals(record))
                && subject.get("source") instanceof Map<?, ?> source) {
            Integer beginLine = integer(source.get("start_line"));
            Integer beginColumn = integer(source.get("start_column"));
            Integer endLine = integer(source.get("end_line"));
            Integer endColumn = integer(source.get("end_column"));
            Object file = source.get("file");
            if (file != null && beginLine != null && beginColumn != null
                    && endLine != null && endColumn != null) {
                return query.sourceRange(String.valueOf(file), beginLine, beginColumn,
                        endLine, endColumn, request);
            }
        }
        return query.source(sourceReference(subject), request);
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Map<String, Object> sourceSlice(Map<String, Object> subject,
                                                    SourceContext source,
                                                    String seed, String parent,
                                                    SemanticIdentity identity) {
        Map<String, Object> out = SemanticRecords.common("source_slice", seed, parent, identity);
        out.put("id", "source:sha256:" + SemanticIdentity.sha256(
                String.valueOf(subject.get("id")) + "\n" + source.sourceRange + "\n"
                        + source.offset).substring(0, 32));
        Map<String, Object> subjectRef = new LinkedHashMap<>();
        subjectRef.put("record", subject.get("record"));
        subjectRef.put("id", subject.get("id"));
        if (subject.get("kind") != null) subjectRef.put("kind", subject.get("kind"));
        if (subject.get("qualified_name") != null) {
            subjectRef.put("qualified_name", subject.get("qualified_name"));
        }
        out.put("subject", subjectRef);
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("file", source.sourceFile);
        if (source.startLine != null) range.put("start_line", source.startLine);
        if (source.endLine != null) range.put("end_line", source.endLine);
        range.put("declaration_range", source.sourceRange);
        range.put("offset", source.offset);
        range.put("limit", source.limit);
        range.put("truncated", source.truncated);
        out.put("source", range);
        out.put("snippet", source.snippet);
        out.put("origin", "extracted");
        out.put("resolution_status", "exact");
        out.putAll(SemanticRecords.lineage(subject));
        return out;
    }
}
