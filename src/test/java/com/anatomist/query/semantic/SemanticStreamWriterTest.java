package com.anatomist.query.semantic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Map;

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
                    () -> writer.write(Map.of("record", "entity")));
        } catch (SemanticStreamWriter.BrokenPipeException expectedOnClose) {
            // The same failure is allowed to surface again during close.
        }
    }
}
