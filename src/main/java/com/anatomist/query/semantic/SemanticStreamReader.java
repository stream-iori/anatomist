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

/** Strict reader for canonical header-based semantic streams. */
public final class SemanticStreamReader {
    private static final Set<String> RECORD_IDENTITY_FIELDS = Set.of(
            "contract", "identity", "index_revision_id", "source_snapshot_id",
            "semantic_profile_id", "parent_seed_id");

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
        boolean headerSeen = false;
        boolean streamEvidence = false;
        boolean upstreamComplete = true;
        String currentSeed = null;
        List<SemanticRecord> current = new ArrayList<>();
        Set<String> evidenceSeeds = new HashSet<>();
        SemanticIdentity streamIdentity = null;
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
                Map<String, Object> raw = parse(line);
                if (!headerSeen) {
                    streamIdentity = decodeHeader(raw, expected);
                    headerSeen = true;
                    continue;
                }
                if ("stream_header".equals(string(raw, "record"))) {
                    throw new SemanticStreamException("DUPLICATE_STREAM_HEADER",
                            "stream_header may appear only once");
                }
                if (streamEvidence) throw new SemanticStreamException(
                        "RECORD_AFTER_STREAM_EVIDENCE", "stream evidence must be the final record");

                SemanticRecord record = decodeWire(raw, streamIdentity);
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

        if (!headerSeen) throw new SemanticStreamException(
                "MISSING_STREAM_HEADER", "input must begin with stream_header");
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
    private static Map<String, Object> parse(String line) {
        Object parsed = Json.parseTree(line, SemanticRecords.MAX_JSON_DEPTH);
        if (!(parsed instanceof Map<?, ?> raw)) throw new SemanticStreamException(
                "INVALID_RECORD", "each input line must be a JSON object");
        return (Map<String, Object>) raw;
    }

    private static SemanticIdentity decodeHeader(Map<String, Object> raw,
                                                  SemanticIdentity expected) {
        if (!"stream_header".equals(string(raw, "record"))) {
            throw new SemanticStreamException("MISSING_STREAM_HEADER",
                    "input must begin with stream_header");
        }
        if (!SemanticRecords.CONTRACT.equals(string(raw, "contract"))) {
            throw new SemanticStreamException("CONTRACT_MISMATCH",
                    "expected " + SemanticRecords.CONTRACT);
        }
        if (!(raw.get("identity") instanceof Map<?, ?> identity)) {
            throw new SemanticStreamException("INVALID_STREAM_HEADER",
                    "stream_header requires identity");
        }
        SemanticIdentity actual = new SemanticIdentity(requiredString(identity, "index_revision_id"),
                requiredString(identity, "source_snapshot_id"),
                requiredString(identity, "semantic_profile_id"));
        validateIdentity(new SemanticRecord.Header("stream_header", null, null,
                actual.indexRevisionId(), actual.sourceSnapshotId(),
                actual.semanticProfileId()), expected);
        return actual;
    }

    private static SemanticRecord decodeWire(Map<String, Object> raw,
                                             SemanticIdentity identity) {
        for (String field : RECORD_IDENTITY_FIELDS) {
            if (raw.containsKey(field)) throw new SemanticStreamException(
                    "NON_CANONICAL_RECORD", field + " belongs only in stream_header");
        }
        Map<String, Object> hydrated = new LinkedHashMap<>(raw);
        hydrated.put("contract", SemanticRecords.CONTRACT);
        hydrated.put("index_revision_id", identity.indexRevisionId());
        hydrated.put("source_snapshot_id", identity.sourceSnapshotId());
        hydrated.put("semantic_profile_id", identity.semanticProfileId());
        return SemanticRecordCodec.decode(hydrated);
    }

    private static void validateIdentity(SemanticRecord.Header header, SemanticIdentity expected) {
        requireEqual(header.indexRevisionId(), expected.indexRevisionId(),
                "index_revision_id", "INDEX_CHANGED_DURING_PIPELINE");
        requireEqual(header.semanticProfileId(), expected.semanticProfileId(),
                "semantic_profile_id", "SEMANTIC_PROFILE_MISMATCH");
        requireEqual(header.sourceSnapshotId(), expected.sourceSnapshotId(),
                "source_snapshot_id", "SOURCE_SNAPSHOT_MISMATCH");
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

    private static String requiredString(Map<?, ?> record, String key) {
        String value = string(record, key);
        if (value == null || value.isBlank()) throw new SemanticStreamException(
                "INVALID_STREAM_HEADER", "identity requires " + key);
        return value;
    }

    private static String string(Map<?, ?> record, String key) {
        Object value = record.get(key);
        if (value == null) return null;
        if (!(value instanceof String text)) throw new SemanticStreamException(
                "INVALID_RECORD", key + " must be a string");
        return text;
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
