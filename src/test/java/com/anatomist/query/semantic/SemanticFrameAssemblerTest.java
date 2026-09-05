package com.anatomist.query.semantic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SemanticFrameAssemblerTest {
    private static final SemanticIdentity ID = new SemanticIdentity(
            "rev:1", "sha256:s", "sha256:p");

    @Test
    void emitsOneTypedFrameWhenSeedEvidenceArrives() {
        List<SemanticStreamReader.SeedFrame> frames = new ArrayList<>();
        SemanticFrameAssembler assembler = new SemanticFrameAssembler(ID, frames::add);

        assembler.accept(entity("seed:1", "p.A"));
        assertTrue(frames.isEmpty());
        assembler.accept(SemanticRecords.seedEvidence("seed:1", null,
                1, true, null, ID));
        assembler.finish();

        assertEquals(1, frames.size());
        assertEquals("seed:1", frames.getFirst().seedId());
        assertEquals("p.A", ((SemanticRecord.Entity) frames.getFirst()
                .records().getFirst()).id());
    }

    @Test
    void rejectsUnfinishedSeedAndIntermediateStreamEvidence() {
        SemanticFrameAssembler unfinished = new SemanticFrameAssembler(ID, frame -> {});
        unfinished.accept(entity("seed:1", "p.A"));
        SemanticStreamReader.SemanticStreamException missing = assertThrows(
                SemanticStreamReader.SemanticStreamException.class, unfinished::finish);
        assertEquals("MISSING_SEED_EVIDENCE", missing.code());

        SemanticFrameAssembler stream = new SemanticFrameAssembler(ID, frame -> {});
        SemanticStreamReader.SemanticStreamException finalEvidence = assertThrows(
                SemanticStreamReader.SemanticStreamException.class,
                () -> stream.accept(SemanticRecords.streamEvidence(1, 1, true, ID)));
        assertEquals("RECORD_AFTER_STREAM_EVIDENCE", finalEvidence.code());
    }

    @Test
    void rejectsIdentityMismatchBeforeCallingNextStage() {
        List<SemanticStreamReader.SeedFrame> frames = new ArrayList<>();
        SemanticFrameAssembler assembler = new SemanticFrameAssembler(ID, frames::add);
        Map<String, Object> record = entity("seed:1", "p.A");
        record.put("index_revision_id", "rev:other");

        SemanticStreamReader.SemanticStreamException failure = assertThrows(
                SemanticStreamReader.SemanticStreamException.class,
                () -> assembler.accept(record));
        assertEquals("INDEX_CHANGED_DURING_PIPELINE", failure.code());
        assertTrue(frames.isEmpty());
    }

    private static Map<String, Object> entity(String seed, String id) {
        Map<String, Object> record = new LinkedHashMap<>(
                SemanticRecords.common("entity", seed, null, ID));
        record.put("id", id);
        record.put("kind", "type");
        record.put("qualified_name", id);
        return record;
    }
}
