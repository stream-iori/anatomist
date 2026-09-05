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
    void acceptsUnknownFieldsAndCompleteFraming() {
        String input = line(Map.of(
                "record", "entity", "contract", SemanticRecords.CONTRACT,
                "seed_id", "seed:1", "index_revision_id", "rev:1",
                "source_snapshot_id", "sha256:s", "semantic_profile_id", "sha256:p",
                "id", "p.A", "future_field", 42))
                + line(SemanticRecords.seedEvidence("seed:1", null, 1, true, null, ID))
                + line(SemanticRecords.streamEvidence(1, 1, true, ID));
        List<SemanticRecord> rows = new ArrayList<>();
        SemanticStreamReader.Summary summary = SemanticStreamReader.read(bytes(input),
                Set.of("entity"), false, ID, rows::add);
        assertEquals(1, rows.size());
        assertTrue(summary.framed());
    }

    @Test
    void rejectsUnknownRecordAndMissingFinalEvidence() {
        String unknown = "{\"record\":\"future\",\"contract\":\"semantic-stream/v1\","
                + "\"seed_id\":\"seed:1\",\"index_revision_id\":\"rev:1\","
                + "\"semantic_profile_id\":\"sha256:p\"}\n";
        SemanticStreamException unsupported = assertThrows(SemanticStreamException.class,
                () -> SemanticStreamReader.read(bytes(unknown), Set.of("entity"), false, ID, row -> {}));
        assertEquals("UNSUPPORTED_RECORD_TYPE", unsupported.code());

        String dataOnly = line(Map.of(
                "record", "entity", "contract", SemanticRecords.CONTRACT,
                "seed_id", "seed:1", "index_revision_id", "rev:1",
                "semantic_profile_id", "sha256:p", "id", "p.A"));
        SemanticStreamException missing = assertThrows(SemanticStreamException.class,
                () -> SemanticStreamReader.read(bytes(dataOnly), Set.of("entity"), false, ID, row -> {}));
        assertEquals("MISSING_STREAM_EVIDENCE", missing.code());
        assertFalse(SemanticStreamReader.read(bytes(dataOnly), Set.of("entity"), true,
                ID, row -> {}).framed());
    }

    @Test
    void rejectsRevisionMismatch() {
        String data = line(Map.of(
                "record", "entity", "contract", SemanticRecords.CONTRACT,
                "seed_id", "seed:1", "index_revision_id", "rev:other",
                "semantic_profile_id", "sha256:p", "id", "p.A"));
        SemanticStreamException failure = assertThrows(SemanticStreamException.class,
                () -> SemanticStreamReader.read(bytes(data), Set.of("entity"), true, ID, row -> {}));
        assertEquals("INDEX_CHANGED_DURING_PIPELINE", failure.code());
    }

    @Test
    void rejectsExcessiveJsonNesting() {
        String nested = "[".repeat(65) + "0" + "]".repeat(65);
        String data = "{\"record\":\"entity\",\"contract\":\"semantic-stream/v1\","
                + "\"seed_id\":\"seed:1\",\"index_revision_id\":\"rev:1\","
                + "\"semantic_profile_id\":\"sha256:p\",\"future\":" + nested + "}\n";
        SemanticStreamException failure = assertThrows(SemanticStreamException.class,
                () -> SemanticStreamReader.read(bytes(data), Set.of("entity"), true, ID, row -> {}));
        assertEquals("MALFORMED_NDJSON", failure.code());
    }

    @Test
    void propagatesUpstreamPartialEvidence() {
        String input = line(Map.of(
                "record", "entity", "contract", SemanticRecords.CONTRACT,
                "seed_id", "seed:1", "index_revision_id", "rev:1",
                "source_snapshot_id", "sha256:s", "semantic_profile_id", "sha256:p",
                "id", "p.A"))
                + line(SemanticRecords.seedEvidence("seed:1", null, 1, false,
                "QUERY_LIMIT_TRUNCATED", true, ID))
                + line(SemanticRecords.streamEvidence(1, 1, false, true, ID));
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
        String wrongId = line(Map.of(
                "record", "entity", "contract", SemanticRecords.CONTRACT,
                "seed_id", "seed:1", "index_revision_id", "rev:1",
                "semantic_profile_id", "sha256:p", "id", 42));
        SemanticStreamException failure = assertThrows(SemanticStreamException.class,
                () -> SemanticStreamReader.read(bytes(wrongId), Set.of("entity"), true,
                        ID, row -> {}));
        assertEquals("INVALID_RECORD", failure.code());

        String invalidRange = line(Map.of(
                "record", "call_site", "contract", SemanticRecords.CONTRACT,
                "seed_id", "seed:1", "index_revision_id", "rev:1",
                "semantic_profile_id", "sha256:p", "id", "callsite:1", "caller", "p.A#m()",
                "source", Map.of("file", "A.java", "start_line", 4, "start_column", 3,
                        "end_line", 3, "end_column", 1)));
        failure = assertThrows(SemanticStreamException.class,
                () -> SemanticStreamReader.read(bytes(invalidRange), Set.of("call_site"), true,
                        ID, row -> {}));
        assertEquals("INVALID_RECORD", failure.code());
    }

    @Test
    void deliversCompletedSeedBeforeReadingStreamTail() {
        String prefix = line(Map.of(
                "record", "entity", "contract", SemanticRecords.CONTRACT,
                "seed_id", "seed:1", "index_revision_id", "rev:1",
                "source_snapshot_id", "sha256:s", "semantic_profile_id", "sha256:p",
                "id", "p.A"))
                + line(SemanticRecords.seedEvidence("seed:1", null, 1, true, null, ID));
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
