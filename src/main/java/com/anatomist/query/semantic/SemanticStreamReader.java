package com.anatomist.query.semantic;

import com.anatomist.json.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Strict, seed-framed semantic-stream reader with bounded buffering. */
public final class SemanticStreamReader {
    private SemanticStreamReader() {}

    public static Summary read(InputStream input, Set<String> acceptedRecords,
                               boolean acceptUnframed, SemanticIdentity expected,
                               Consumer<SemanticRecord> consumer) {
        return readFrames(input, acceptedRecords, acceptUnframed, expected,
                frame -> frame.records().forEach(consumer));
    }

    /** Delivers one bounded seed only after its seed evidence is available. */
    public static Summary readFrames(InputStream input, Set<String> acceptedRecords,
                                     boolean acceptUnframed, SemanticIdentity expected,
                                     Consumer<SeedFrame> consumer) {
        int records = 0;
        int data = 0;
        int seeds = 0;
        boolean streamEvidence = false;
        boolean upstreamComplete = true;
        String currentSeed = null;
        List<SemanticRecord> current = new ArrayList<>();
        Set<String> evidenceSeeds = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (line.getBytes(StandardCharsets.UTF_8).length > SemanticRecords.MAX_LINE_BYTES) {
                    throw new SemanticStreamException("INPUT_LINE_TOO_LARGE",
                            "semantic input line exceeds 1 MiB");
                }
                if (++records > SemanticRecords.MAX_RECORDS) {
                    throw new SemanticStreamException("INPUT_RECORD_LIMIT",
                            "semantic input exceeds " + SemanticRecords.MAX_RECORDS + " records");
                }
                if (streamEvidence) throw new SemanticStreamException(
                        "RECORD_AFTER_STREAM_EVIDENCE", "stream evidence must be the final record");

                SemanticRecord record = decode(line, expected);
                if (record instanceof SemanticRecord.Evidence evidence) {
                    if ("stream".equals(evidence.scope())) {
                        if (currentSeed != null) {
                            if (!acceptUnframed) throw missingSeed(currentSeed);
                            consumer.accept(new SeedFrame(currentSeed, List.copyOf(current),
                                    syntheticEvidence(currentSeed, expected)));
                            seeds++;
                            current.clear();
                            currentSeed = null;
                            upstreamComplete = false;
                        }
                        streamEvidence = true;
                        upstreamComplete &= evidence.complete();
                        continue;
                    }
                    String seed = evidence.seedId();
                    if (!evidenceSeeds.add(seed)) throw new SemanticStreamException(
                            "DUPLICATE_SEED_EVIDENCE", "seed evidence appears more than once: " + seed);
                    if (evidenceSeeds.size() > SemanticRecords.MAX_SEEDS) {
                        throw new SemanticStreamException("INPUT_SEED_LIMIT",
                                "semantic input exceeds " + SemanticRecords.MAX_SEEDS + " seeds");
                    }
                    if (currentSeed != null && !currentSeed.equals(seed)) {
                        if (!acceptUnframed) throw missingSeed(currentSeed);
                        consumer.accept(new SeedFrame(currentSeed, List.copyOf(current),
                                syntheticEvidence(currentSeed, expected)));
                        seeds++;
                        current.clear();
                        currentSeed = null;
                        upstreamComplete = false;
                    }
                    List<SemanticRecord> framed = currentSeed == null ? List.of() : List.copyOf(current);
                    consumer.accept(new SeedFrame(seed, framed, evidence));
                    seeds++;
                    current.clear();
                    currentSeed = null;
                    upstreamComplete &= evidence.complete();
                    continue;
                }

                if (!acceptedRecords.contains(record.type())) {
                    throw new SemanticStreamException("INPUT_TYPE_MISMATCH",
                            "operation does not accept record " + record.type());
                }
                if (currentSeed == null) currentSeed = record.seedId();
                else if (!currentSeed.equals(record.seedId())) {
                    if (!acceptUnframed) throw missingSeed(currentSeed);
                    consumer.accept(new SeedFrame(currentSeed, List.copyOf(current),
                            syntheticEvidence(currentSeed, expected)));
                    seeds++;
                    current.clear();
                    currentSeed = record.seedId();
                    upstreamComplete = false;
                }
                if (current.size() >= SemanticRecords.MAX_RECORDS_PER_SEED) {
                    throw new SemanticStreamException("INPUT_SEED_RECORD_LIMIT",
                            "seed exceeds " + SemanticRecords.MAX_RECORDS_PER_SEED + " records: "
                                    + currentSeed);
                }
                current.add(record);
                data++;
            }
        } catch (IOException failure) {
            throw new SemanticStreamException("INPUT_IO_FAILED", failure.getMessage());
        } catch (IllegalArgumentException failure) {
            if (failure instanceof SemanticStreamException semantic) throw semantic;
            throw new SemanticStreamException("MALFORMED_NDJSON", failure.getMessage());
        }

        if (!acceptUnframed && !streamEvidence) throw new SemanticStreamException(
                "MISSING_STREAM_EVIDENCE", "input ended without final stream evidence");
        if (currentSeed != null) {
            if (!acceptUnframed) throw missingSeed(currentSeed);
            consumer.accept(new SeedFrame(currentSeed, List.copyOf(current),
                    syntheticEvidence(currentSeed, expected)));
            seeds++;
            upstreamComplete = false;
        }
        return new Summary(records, data, seeds, streamEvidence,
                streamEvidence && upstreamComplete);
    }

    @SuppressWarnings("unchecked")
    private static SemanticRecord decode(String line, SemanticIdentity expected) {
        Object parsed = Json.parseTree(line, SemanticRecords.MAX_JSON_DEPTH);
        if (!(parsed instanceof Map<?, ?> raw)) throw new SemanticStreamException(
                "INVALID_RECORD", "each input line must be a JSON object");
        Map<String, Object> record = (Map<String, Object>) raw;
        if (!SemanticRecords.CONTRACT.equals(string(record, "contract"))) {
            throw new SemanticStreamException("CONTRACT_MISMATCH",
                    "expected " + SemanticRecords.CONTRACT);
        }
        validateIdentity(new SemanticRecord.Header(string(record, "record"),
                string(record, "seed_id"), string(record, "parent_seed_id"),
                string(record, "index_revision_id"), string(record, "source_snapshot_id"),
                string(record, "semantic_profile_id")), expected);
        SemanticRecord typed = SemanticRecordCodec.decode(record);
        return typed;
    }

    private static void validateIdentity(SemanticRecord.Header header, SemanticIdentity expected) {
        requireEqual(header.indexRevisionId(), expected.indexRevisionId(),
                "index_revision_id", "INDEX_CHANGED_DURING_PIPELINE");
        requireEqual(header.semanticProfileId(), expected.semanticProfileId(),
                "semantic_profile_id", "SEMANTIC_PROFILE_MISMATCH");
        if (header.sourceSnapshotId() != null
                && !expected.sourceSnapshotId().equals(header.sourceSnapshotId())) {
            throw new SemanticStreamException("SOURCE_SNAPSHOT_MISMATCH",
                    "input source_snapshot_id does not match the index");
        }
    }

    static void validateTypedRecord(SemanticRecord record, Set<String> acceptedRecords,
                                    SemanticIdentity expected) {
        validateIdentity(record.header(), expected);
        if (!(record instanceof SemanticRecord.Evidence)
                && !acceptedRecords.contains(record.type())) {
            throw new SemanticStreamException("INPUT_TYPE_MISMATCH",
                    "operation does not accept record " + record.type());
        }
    }

    private static void requireEqual(String actual, String expected, String field, String code) {
        if (!expected.equals(actual)) throw new SemanticStreamException(code,
                field + " does not match the current index");
    }

    private static SemanticRecord.Evidence syntheticEvidence(String seed,
                                                              SemanticIdentity identity) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("record", "evidence");
        raw.put("contract", SemanticRecords.CONTRACT);
        raw.put("seed_id", seed);
        raw.put("index_revision_id", identity.indexRevisionId());
        raw.put("source_snapshot_id", identity.sourceSnapshotId());
        raw.put("semantic_profile_id", identity.semanticProfileId());
        raw.put("scope", "seed");
        raw.put("status", "partial");
        raw.put("coverage", "unknown");
        raw.put("truncated", false);
        raw.put("negative_conclusion_safe", false);
        return new SemanticRecord.Evidence(new SemanticRecord.Header("evidence", seed, null,
                identity.indexRevisionId(), identity.sourceSnapshotId(), identity.semanticProfileId()),
                "seed", "partial", "unknown", false, false, raw);
    }

    private static SemanticStreamException missingSeed(String seed) {
        return new SemanticStreamException("MISSING_SEED_EVIDENCE",
                "input seed lacks evidence: " + seed);
    }

    private static String string(Map<String, Object> record, String key) {
        Object value = record.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public record SeedFrame(String seedId, List<SemanticRecord> records,
                            SemanticRecord.Evidence evidence) {}

    public record Summary(int records, int dataRecords, int seeds,
                          boolean framed, boolean complete) {}

    public static final class SemanticStreamException extends IllegalArgumentException {
        private final String code;

        public SemanticStreamException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() { return code; }
    }
}
