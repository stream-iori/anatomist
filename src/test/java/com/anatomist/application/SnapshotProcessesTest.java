package com.anatomist.application;

import com.anatomist.json.Json;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotProcessesTest {
    @TempDir Path directory;

    @Test void unfinishedReceiptCanRecoverItsOwnedProcess() throws Exception {
        Process child = new ProcessBuilder("sleep", "60").start();
        try {
            Path receipt = Files.createDirectories(directory.resolve("processes"))
                    .resolve("0123456789abcdef0123456789abcdef.json.tmp");
            Files.writeString(receipt, Json.writeCompact(Map.of("kind", "snapshot-process-v1",
                    "pid", child.pid(), "started", child.info().startInstant().orElseThrow().toString())));
            assertEquals(true, SnapshotProcesses.recover(directory, false).getFirst().get("owned"));
            assertTrue(child.isAlive());
            assertTrue(Files.exists(receipt));
            SnapshotProcesses.recover(directory, true);
            assertTrue(child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(Files.exists(receipt));
        } finally {
            child.destroyForcibly();
            child.waitFor();
        }
    }

    @Test void reusedPidAndMalformedReceiptDoNotAuthorizeKilling() throws Exception {
        Process child = new ProcessBuilder("sleep", "60").start();
        try {
            Path receipts = Files.createDirectories(directory.resolve("processes"));
            Path stale = receipts.resolve("0123456789abcdef0123456789abcdef.json");
            Path malformed = receipts.resolve("abcdef0123456789abcdef0123456789.json.tmp");
            Files.writeString(stale, Json.writeCompact(Map.of("kind", "snapshot-process-v1",
                    "pid", child.pid(), "started", "1970-01-01T00:00:00Z")));
            Files.writeString(malformed, "{broken");
            assertTrue(SnapshotProcesses.recover(directory, true).stream()
                    .allMatch(row -> Boolean.FALSE.equals(row.get("owned"))));
            assertTrue(child.isAlive());
            assertTrue(Files.exists(malformed));
            assertFalse(Files.exists(stale));
        } finally {
            child.destroyForcibly();
            child.waitFor();
        }
    }
}
