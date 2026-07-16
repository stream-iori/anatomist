package com.anatomist.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class WatchLeaseTest {

    @Test
    void onlyOneAutoIndexWatchCanOwnAnIndex(@TempDir Path tmp) {
        Path db = tmp.resolve("index.db");
        try (WatchLease ignored = WatchLease.acquire(db)) {
            assertThrows(IndexLock.LockTimeoutException.class, () -> WatchLease.acquire(db));
        }
    }
}
