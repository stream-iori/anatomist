package com.anatomist.query.semantic;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("stream-performance")
class SemanticStreamStressIT {
    private static final int SEEDS = 100_000;
    private static final SemanticIdentity ID =
            new SemanticIdentity("rev:stress", "sha256:stress", "sha256:profile");

    @Test
    void streamsOneHundredThousandSeedsWithin128MiB() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-Xmx128m", "-cp",
                System.getProperty("java.class.path"), getClass().getName(), "--probe")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        assertTrue(output.contains("OK seeds=100000 data=100000 max_frame=1"), output);
    }

    public static void main(String[] args) {
        AtomicInteger frames = new AtomicInteger();
        AtomicInteger maxFrame = new AtomicInteger();
        SemanticStreamReader.Summary summary = SemanticStreamReader.readFrames(
                new GeneratedInput(), Set.of("entity"), false, ID, frame -> {
                    frames.incrementAndGet();
                    maxFrame.accumulateAndGet(frame.records().size(), Math::max);
                });
        if (frames.get() != SEEDS || summary.dataRecords() != SEEDS || maxFrame.get() != 1) {
            throw new AssertionError("unexpected summary: " + summary);
        }
        System.out.printf("OK seeds=%d data=%d max_frame=%d%n",
                frames.get(), summary.dataRecords(), maxFrame.get());
    }

    private static final class GeneratedInput extends InputStream {
        private int line;
        private byte[] current = new byte[0];
        private int offset;

        @Override public int read() {
            if (!ensureLine()) return -1;
            return current[offset++] & 0xff;
        }

        @Override public int read(byte[] target, int start, int length) {
            if (!ensureLine()) return -1;
            int copied = Math.min(length, current.length - offset);
            System.arraycopy(current, offset, target, start, copied);
            offset += copied;
            return copied;
        }

        private boolean ensureLine() {
            if (offset < current.length) return true;
            if (line > SEEDS * 2) return false;
            String json;
            if (line == SEEDS * 2) {
                json = "{\"record\":\"evidence\",\"contract\":\"semantic-stream/v1\","
                        + "\"index_revision_id\":\"rev:stress\","
                        + "\"source_snapshot_id\":\"sha256:stress\","
                        + "\"semantic_profile_id\":\"sha256:profile\","
                        + "\"scope\":\"stream\",\"status\":\"positive\","
                        + "\"coverage\":\"complete\",\"truncated\":false,"
                        + "\"negative_conclusion_safe\":false}";
            } else {
                int seed = line / 2;
                String common = "\"contract\":\"semantic-stream/v1\",\"seed_id\":\"seed:"
                        + seed + "\",\"index_revision_id\":\"rev:stress\","
                        + "\"source_snapshot_id\":\"sha256:stress\","
                        + "\"semantic_profile_id\":\"sha256:profile\"";
                if ((line & 1) == 0) {
                    json = "{\"record\":\"entity\"," + common + ",\"id\":\"p.A#"
                            + seed + "()\",\"kind\":\"callable\"}";
                } else {
                    json = "{\"record\":\"evidence\"," + common
                            + ",\"scope\":\"seed\",\"status\":\"positive\","
                            + "\"coverage\":\"complete\",\"truncated\":false,"
                            + "\"negative_conclusion_safe\":false}";
                }
            }
            line++;
            current = (json + "\n").getBytes(StandardCharsets.UTF_8);
            offset = 0;
            return true;
        }
    }
}
