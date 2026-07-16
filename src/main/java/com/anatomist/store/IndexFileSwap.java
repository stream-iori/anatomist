package com.anatomist.store;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Atomically promotes a complete replacement SQLite database where supported. */
public final class IndexFileSwap {
    private IndexFileSwap() {}

    public static void promote(Path temporary, Path live) throws IOException {
        Files.deleteIfExists(live.resolveSibling(live.getFileName() + "-wal"));
        Files.deleteIfExists(live.resolveSibling(live.getFileName() + "-shm"));
        Files.deleteIfExists(live.resolveSibling(live.getFileName() + "-journal"));
        try {
            Files.move(temporary, live, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, live, StandardCopyOption.REPLACE_EXISTING);
        }
        moveSidecar(temporary, live, "-wal");
        moveSidecar(temporary, live, "-shm");
    }

    private static void moveSidecar(Path temporary, Path live, String suffix) throws IOException {
        Path source = temporary.resolveSibling(temporary.getFileName() + suffix);
        if (!Files.exists(source)) return;
        Files.move(source, live.resolveSibling(live.getFileName() + suffix),
                StandardCopyOption.REPLACE_EXISTING);
    }
}
