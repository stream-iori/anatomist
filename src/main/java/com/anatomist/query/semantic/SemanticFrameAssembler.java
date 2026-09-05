package com.anatomist.query.semantic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Turns typed stage output into the exact seed frames consumed by the next stage. */
public final class SemanticFrameAssembler implements Consumer<Map<String, Object>> {
    private final SemanticIdentity identity;
    private final Consumer<SemanticStreamReader.SeedFrame> consumer;
    private final Set<String> evidenceSeeds = new HashSet<>();
    private final List<SemanticRecord> current = new ArrayList<>();
    private String currentSeed;
    private int records;

    public SemanticFrameAssembler(SemanticIdentity identity,
                                  Consumer<SemanticStreamReader.SeedFrame> consumer) {
        this.identity = identity;
        this.consumer = consumer;
    }

    @Override public void accept(Map<String, Object> raw) {
        if (++records > SemanticRecords.MAX_RECORDS) {
            throw new SemanticStreamReader.SemanticStreamException("INPUT_RECORD_LIMIT",
                    "semantic stage output exceeds " + SemanticRecords.MAX_RECORDS + " records");
        }
        SemanticRecord record = SemanticRecordCodec.decode(raw);
        SemanticStreamReader.validateTypedRecord(record, Set.of(record.type()), identity);
        if (record instanceof SemanticRecord.Evidence evidence) {
            if ("stream".equals(evidence.scope())) {
                throw new SemanticStreamReader.SemanticStreamException(
                        "RECORD_AFTER_STREAM_EVIDENCE",
                        "intermediate stage emitted stream evidence");
            }
            String seed = evidence.seedId();
            if (!evidenceSeeds.add(seed)) {
                throw new SemanticStreamReader.SemanticStreamException(
                        "DUPLICATE_SEED_EVIDENCE",
                        "seed evidence appears more than once: " + seed);
            }
            if (evidenceSeeds.size() > SemanticRecords.MAX_SEEDS) {
                throw new SemanticStreamReader.SemanticStreamException("INPUT_SEED_LIMIT",
                        "semantic stage output exceeds " + SemanticRecords.MAX_SEEDS + " seeds");
            }
            if (currentSeed != null && !currentSeed.equals(seed)) {
                throw missingSeed(currentSeed);
            }
            List<SemanticRecord> framed = currentSeed == null
                    ? List.of() : List.copyOf(current);
            current.clear();
            currentSeed = null;
            consumer.accept(new SemanticStreamReader.SeedFrame(seed, framed, evidence));
            return;
        }

        if (currentSeed == null) currentSeed = record.seedId();
        else if (!currentSeed.equals(record.seedId())) throw missingSeed(currentSeed);
        if (current.size() >= SemanticRecords.MAX_RECORDS_PER_SEED) {
            throw new SemanticStreamReader.SemanticStreamException("INPUT_SEED_RECORD_LIMIT",
                    "seed exceeds " + SemanticRecords.MAX_RECORDS_PER_SEED
                            + " records: " + currentSeed);
        }
        current.add(record);
    }

    public void finish() {
        if (currentSeed != null) throw missingSeed(currentSeed);
    }

    private static SemanticStreamReader.SemanticStreamException missingSeed(String seed) {
        return new SemanticStreamReader.SemanticStreamException("MISSING_SEED_EVIDENCE",
                "stage output seed lacks evidence: " + seed);
    }
}
