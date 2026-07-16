package com.anatomist.store;

import com.anatomist.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable freshness state for a live index. Kept outside SQLite so a failed
 * replacement never mutates the last known-good graph. */
public final class IndexStateStore {

    public enum State { IDLE, INCREMENTAL, REBUILDING, STALE, FAILED }

    public record Snapshot(State state, String reason, long dirtyGeneration,
                           String startedAt, String temporaryIndex) {
        public boolean fresh() { return state == State.IDLE; }
    }

    private IndexStateStore() {}

    public static Path pathFor(Path indexPath) {
        return indexPath.resolveSibling(indexPath.getFileName() + ".state.json");
    }

    public static Snapshot read(Path indexPath) {
        Path statePath = pathFor(indexPath);
        if (!Files.isRegularFile(statePath)) return new Snapshot(State.IDLE, null, 0, null, null);
        try {
            Object tree = Json.parseTree(Files.readString(statePath, StandardCharsets.UTF_8));
            if (!(tree instanceof Map<?, ?> values)) return failed("invalid state file");
            Object rawState = values.get("state");
            State state = rawState == null ? State.FAILED : State.valueOf(String.valueOf(rawState));
            return new Snapshot(state, string(values, "reason"), number(values, "dirty_generation"),
                    string(values, "started_at"), string(values, "temporary_index"));
        } catch (Exception ex) {
            return failed("invalid state file: " + ex.getMessage());
        }
    }

    public static void write(Path indexPath, State state, String reason,
                             long dirtyGeneration, Path temporaryIndex) {
        Path target = pathFor(indexPath);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("state", state.name());
        values.put("reason", reason);
        values.put("dirty_generation", dirtyGeneration);
        values.put("started_at", Instant.now().toString());
        if (temporaryIndex != null) values.put("temporary_index", temporaryIndex.toString());
        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary, Json.writePretty(values), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write index state " + target, ex);
        }
    }

    public static void clear(Path indexPath) {
        try {
            Files.deleteIfExists(pathFor(indexPath));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to clear index state", ex);
        }
    }

    public static void recoverInterrupted(Path indexPath) {
        Snapshot snapshot = read(indexPath);
        if (snapshot.state != State.REBUILDING && snapshot.state != State.INCREMENTAL) return;
        cleanupTemporary(snapshot.temporaryIndex);
        write(indexPath, State.STALE, "previous watch process interrupted", snapshot.dirtyGeneration, null);
    }

    public static void cleanupTemporary(String temporaryIndex) {
        if (temporaryIndex == null || temporaryIndex.isBlank()) return;
        Path path = Path.of(temporaryIndex);
        try {
            Files.deleteIfExists(path);
            Files.deleteIfExists(path.resolveSibling(path.getFileName() + "-wal"));
            Files.deleteIfExists(path.resolveSibling(path.getFileName() + "-shm"));
            Files.deleteIfExists(path.resolveSibling(path.getFileName() + "-journal"));
        } catch (IOException ignored) {
            // The next writer will report an actionable filesystem error.
        }
    }

    private static Snapshot failed(String reason) {
        return new Snapshot(State.FAILED, reason, 0, null, null);
    }

    private static String string(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static long number(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value instanceof Number number ? number.longValue() : 0;
    }
}
