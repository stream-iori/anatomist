package com.anatomist.query.semantic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SemanticStreamWriterTest {
    @Test
    void turnsPrintStreamIoFailureIntoBrokenPipe() {
        PrintStream sink = new PrintStream(new OutputStream() {
            @Override public void write(int value) throws IOException {
                throw new IOException("closed");
            }
        });
        try (SemanticStreamWriter writer = new SemanticStreamWriter(sink, "ndjson")) {
            assertThrows(SemanticStreamWriter.BrokenPipeException.class,
                    () -> writer.write(SemanticRecords.common(
                            "entity", "seed:1", null,
                            new SemanticIdentity("r", "s", "p"))));
        } catch (SemanticStreamWriter.BrokenPipeException expectedOnClose) {
            // The same failure is allowed to surface again during close.
        }
    }

    @Test
    void writesIdentityOnceAndOneEvidencePerLogicalSeed() {
        SemanticIdentity identity = new SemanticIdentity("rev:1", "sha256:s", "sha256:p");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (SemanticStreamWriter writer = new SemanticStreamWriter(
                new PrintStream(bytes, true, StandardCharsets.UTF_8), "ndjson")) {
            Map<String, Object> child = SemanticRecords.common(
                    "entity", "seed:child", "seed:root", identity);
            child.put("id", "p.A");
            writer.write(child);
            writer.write(SemanticRecords.seedEvidence(
                    "seed:child", "seed:root", 1, true, null, identity));
            writer.write(SemanticRecords.seedEvidence(
                    "seed:root", null, 1, true, null, identity));
            writer.write(SemanticRecords.streamEvidence(1, 1, true, identity));
        }
        List<String> lines = bytes.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(4, lines.size());
        assertTrue(lines.getFirst().contains("\"record\":\"stream_header\""));
        assertTrue(lines.get(1).contains("\"seed_id\":\"seed:root\""));
        assertEquals(2, lines.stream().filter(line -> line.contains("\"record\":\"evidence\""))
                .count());
        for (String line : lines.subList(1, lines.size())) {
            assertFalse(line.contains("index_revision_id"));
            assertFalse(line.contains("parent_seed_id"));
        }
    }

    @Test
    void jsonIsATerminalEnvelope() {
        SemanticIdentity identity = new SemanticIdentity("rev:1", "sha256:s", "sha256:p");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (SemanticStreamWriter writer = new SemanticStreamWriter(
                new PrintStream(bytes, true, StandardCharsets.UTF_8), "json")) {
            Map<String, Object> row = SemanticRecords.common("entity", "seed:1", null, identity);
            row.put("id", "p.A");
            writer.write(row);
            writer.write(SemanticRecords.seedEvidence("seed:1", null, 1, true, null, identity));
            writer.write(SemanticRecords.streamEvidence(1, 1, true, identity));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) com.anatomist.json.Json.parseTree(
                bytes.toString(StandardCharsets.UTF_8));
        assertEquals(SemanticRecords.CONTRACT, envelope.get("contract"));
        assertEquals(1, ((List<?>) envelope.get("results")).size());
        assertTrue(envelope.get("identity") instanceof Map);
        assertTrue(envelope.get("evidence") instanceof Map);
    }
}
