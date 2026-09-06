package com.anatomist.query.semantic;

import com.anatomist.query.semantic.SemanticStreamReader.SemanticStreamException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SemanticStreamReaderTest {
    private static final SemanticIdentity ID = new SemanticIdentity("rev:1", "sha256:s", "sha256:p");

    @Test
    void requiresOneCanonicalHeader() {
        String oldRecord = line(Map.of("record", "entity", "seed_id", "seed:1", "id", "p.A"));
        SemanticStreamException missing = assertThrows(SemanticStreamException.class,
                () -> SemanticStreamReader.read(bytes(oldRecord), Set.of("entity"), true, ID, row -> {}));
        assertEquals("MISSING_STREAM_HEADER", missing.code());

        String duplicate = line(SemanticRecords.streamHeader(ID))
                + line(SemanticRecords.streamHeader(ID));
        SemanticStreamException repeated = assertThrows(SemanticStreamException.class,
                () -> SemanticStreamReader.read(bytes(duplicate), Set.of("entity"), true, ID, row -> {}));
        assertEquals("DUPLICATE_STREAM_HEADER", repeated.code());

        String repeatedIdentity = line(SemanticRecords.streamHeader(ID)) + line(Map.of(
                "record", "entity", "seed_id", "seed:1", "id", "p.A",
                "index_revision_id", "rev:1"));
        SemanticStreamException nonCanonical = assertThrows(SemanticStreamException.class,
                () -> SemanticStreamReader.read(bytes(repeatedIdentity), Set.of("entity"), true,
                        ID, row -> {}));
        assertEquals("NON_CANONICAL_RECORD", nonCanonical.code());
    }

    @Test
    void acceptsUnknownFieldsAndCompleteFraming() {
        String input = line(SemanticRecords.streamHeader(ID)) + line(Map.of(
                "record", "entity", "seed_id", "seed:1",
                "id", "p.A", "future_field", 42))
                + wireLine(SemanticRecords.seedEvidence("seed:1", null, 1, true, null, ID))
                + wireLine(SemanticRecords.streamEvidence(1, 1, true, ID));
        List<SemanticRecord> rows = new ArrayList<>();
        SemanticStreamReader.Summary summary = SemanticStreamReader.read(bytes(input),
                Set.of("entity"), false, ID, rows::add);
        assertEquals(1, rows.size());
        assertTrue(summary.framed());
    }

    @Test
    void rejectsUnknownRecordAndMissingFinalEvidence() {
        String unknown = line(SemanticRecords.streamHeader(ID))
                + line(Map.of("record", "future", "seed_id", "seed:1"));
        SemanticStreamException unsupported = assertThrows(SemanticStreamException.class,
                () -> SemanticStreamReader.read(bytes(unknown), Set.of("entity"), false, ID, row -> {}));
        assertEquals("UNSUPPORTED_RECORD_TYPE", unsupported.code());

        String dataOnly = line(SemanticRecords.streamHeader(ID)) + line(Map.of(
                "record", "entity", "seed_id", "seed:1", "id", "p.A"));
        SemanticStreamException missing = assertThrows(SemanticStreamException.class,
                () -> SemanticStreamReader.read(bytes(dataOnly), Set.of("entity"), false, ID, row -> {}));
        assertEquals("MISSING_STREAM_EVIDENCE", missing.code());
        assertFalse(SemanticStreamReader.read(bytes(dataOnly), Set.of("entity"), true,
                ID, row -> {}).framed());
    }

    @Test
    void rejectsRevisionMismatch() {
        SemanticIdentity other = new SemanticIdentity("rev:other", "sha256:s", "sha256:p");
        String data = line(SemanticRecords.streamHeader(other));
        SemanticStreamException failure = assertThrows(SemanticStreamException.class,
                () -> SemanticStreamReader.read(bytes(data), Set.of("entity"), true, ID, row -> {}));
        assertEquals("INDEX_CHANGED_DURING_PIPELINE", failure.code());
    }

    @Test
    void rejectsExcessiveJsonNesting() {
        String nested = "[".repeat(65) + "0" + "]".repeat(65);
        String data = line(SemanticRecords.streamHeader(ID))
                + "{\"record\":\"entity\",\"seed_id\":\"seed:1\",\"future\":"
                + nested + "}\n";
        SemanticStreamException failure = assertThrows(SemanticStreamException.class,
                () -> SemanticStreamReader.read(bytes(data), Set.of("entity"), true, ID, row -> {}));
        assertEquals("MALFORMED_NDJSON", failure.code());
    }

    @Test
    void propagatesUpstreamPartialEvidence() {
        String input = line(SemanticRecords.streamHeader(ID)) + line(Map.of(
                "record", "entity", "seed_id", "seed:1",
                "id", "p.A"))
                + wireLine(SemanticRecords.seedEvidence("seed:1", null, 1, false,
                "QUERY_LIMIT_TRUNCATED", true, ID))
                + wireLine(SemanticRecords.streamEvidence(1, 1, false, true, ID));
        SemanticStreamReader.Summary summary = SemanticStreamReader.read(bytes(input),
                Set.of("entity"), false, ID, row -> {});
        assertTrue(summary.framed());
        assertFalse(summary.complete());
    }

    @Test
    void rejectsOversizedInputLine() {
        String input = "{\"padding\":\"" + "x".repeat(SemanticRecords.MAX_LINE_BYTES) + "\"}\n";
        SemanticStreamException failure = assertThrows(SemanticStreamException.class,
                () -> SemanticStreamReader.read(bytes(input), Set.of("entity"), true, ID, row -> {}));
        assertEquals("INPUT_LINE_TOO_LARGE", failure.code());
    }

    @Test
    void rejectsMalformedKnownFields() {
        String wrongId = line(SemanticRecords.streamHeader(ID)) + line(Map.of(
                "record", "entity", "seed_id", "seed:1", "id", 42));
        SemanticStreamException failure = assertThrows(SemanticStreamException.class,
                () -> SemanticStreamReader.read(bytes(wrongId), Set.of("entity"), true,
                        ID, row -> {}));
        assertEquals("INVALID_RECORD", failure.code());

        String invalidRange = line(SemanticRecords.streamHeader(ID)) + line(Map.of(
                "record", "call_site", "seed_id", "seed:1",
                "id", "callsite:1", "caller", "p.A#m()",
                "source", Map.of("file", "A.java", "start_line", 4, "start_column", 3,
                        "end_line", 3, "end_column", 1)));
        failure = assertThrows(SemanticStreamException.class,
                () -> SemanticStreamReader.read(bytes(invalidRange), Set.of("call_site"), true,
                        ID, row -> {}));
        assertEquals("INVALID_RECORD", failure.code());
    }

    @Test
    void deliversCompletedSeedBeforeReadingStreamTail() {
        String prefix = line(SemanticRecords.streamHeader(ID)) + line(Map.of(
                "record", "entity", "seed_id", "seed:1",
                "id", "p.A"))
                + wireLine(SemanticRecords.seedEvidence("seed:1", null, 1, true, null, ID));
        AtomicBoolean delivered = new AtomicBoolean();
        SemanticStreamException failure = assertThrows(SemanticStreamException.class,
                () -> SemanticStreamReader.readFrames(new FailingTailInputStream(prefix),
                        Set.of("entity"), false, ID, frame -> delivered.set(true)));
        assertEquals("INPUT_IO_FAILED", failure.code());
        assertTrue(delivered.get(), "a complete seed must be emitted before input EOF");
    }

    private static String line(Map<String, Object> value) {
        return com.anatomist.json.Json.writeCompact(value) + "\n";
    }

    private static String wireLine(Map<String, Object> value) {
        Map<String, Object> wire = new java.util.LinkedHashMap<>(value);
        wire.remove("contract");
        wire.remove("index_revision_id");
        wire.remove("source_snapshot_id");
        wire.remove("semantic_profile_id");
        wire.remove("parent_seed_id");
        return line(wire);
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class FailingTailInputStream extends InputStream {
        private final byte[] bytes;
        private int offset;

        private FailingTailInputStream(String prefix) {
            this.bytes = prefix.getBytes(StandardCharsets.UTF_8);
        }

        @Override public int read() throws IOException {
            if (offset == bytes.length) throw new IOException("tail failed");
            return bytes[offset++] & 0xff;
        }

        @Override public int read(byte[] target, int start, int length) throws IOException {
            if (offset == bytes.length) throw new IOException("tail failed");
            int copied = Math.min(length, bytes.length - offset);
            System.arraycopy(bytes, offset, target, start, copied);
            offset += copied;
            return copied;
        }
    }
}
