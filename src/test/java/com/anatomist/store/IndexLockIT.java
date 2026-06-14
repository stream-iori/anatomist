package com.anatomist.store;

import com.anatomist.model.ExtractionResult;
import com.anatomist.query.QueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class IndexLockIT {

    @Test
    void queryWaitsForIndexToComplete(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("it.db");

        // Initialize schema so queries don't fail on missing tables
        try (IndexLock wl = IndexLock.forWrite(db);
             SqliteStore store = new SqliteStore(db)) {
            store.initSchema();
        }

        CountDownLatch writerStarted = new CountDownLatch(1);
        AtomicLong writerDoneAt = new AtomicLong(0);
        AtomicLong readerStartedAt = new AtomicLong(0);

        Thread writer = new Thread(() -> {
            try (IndexLock wl = IndexLock.forWrite(db);
                 SqliteStore store = new SqliteStore(db)) {
                writerStarted.countDown();
                // Simulate indexing work
                Thread.sleep(400);
                store.write(new ExtractionResult());
                writerDoneAt.set(System.currentTimeMillis());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread reader = new Thread(() -> {
            try {
                writerStarted.await(2, TimeUnit.SECONDS);
                Thread.sleep(50); // ensure writer holds lock
                // This should block until writer releases
                try (QueryService q = new QueryService(db)) {
                    readerStartedAt.set(System.currentTimeMillis());
                    q.search("anything", null, 10);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        writer.start();
        reader.start();
        writer.join(5000);
        reader.join(5000);

        assertTrue(writerDoneAt.get() > 0, "Writer should have completed");
        assertTrue(readerStartedAt.get() > 0, "Reader should have completed");
        assertTrue(readerStartedAt.get() >= writerDoneAt.get() - 50,
                "Reader should start query after writer finishes (writer done at "
                        + writerDoneAt.get() + ", reader at " + readerStartedAt.get() + ")");
    }

    @Test
    void multipleReadersRunConcurrently(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("it.db");

        try (IndexLock wl = IndexLock.forWrite(db);
             SqliteStore store = new SqliteStore(db)) {
            store.initSchema();
        }

        int readerCount = 3;
        CountDownLatch allHoldingLock = new CountDownLatch(readerCount);
        CountDownLatch allDone = new CountDownLatch(readerCount);
        AtomicLong failures = new AtomicLong(0);

        for (int i = 0; i < readerCount; i++) {
            new Thread(() -> {
                try (IndexLock rl = IndexLock.forRead(db, 3000)) {
                    allHoldingLock.countDown();
                    assertTrue(allHoldingLock.await(3, TimeUnit.SECONDS),
                            "All readers should hold read lock concurrently");
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    allDone.countDown();
                }
            }).start();
        }

        assertTrue(allDone.await(5, TimeUnit.SECONDS), "All readers should complete");
        assertEquals(0, failures.get(), "No reader should fail to acquire read lock");
    }
}
