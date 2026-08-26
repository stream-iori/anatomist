package com.anatomist.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexLockTest {

    @Test
    void readLocksCanBeConcurrent(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("test.db");
        CountDownLatch bothHeld = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Void> first = executor.submit(() -> hold(db, false, bothHeld, release));
            Future<Void> second = executor.submit(() -> hold(db, false, bothHeld, release));
            assertTrue(bothHeld.await(2, TimeUnit.SECONDS),
                    "both read locks should be held concurrently");
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void writeLockExcludesRead(@TempDir Path tmp) throws Exception {
        assertExcluded(tmp.resolve("test.db"), true, false);
    }

    @Test
    void writeLockExcludesWrite(@TempDir Path tmp) throws Exception {
        assertExcluded(tmp.resolve("test.db"), true, true);
    }

    @Test
    void readLockExcludesWrite(@TempDir Path tmp) throws Exception {
        assertExcluded(tmp.resolve("test.db"), false, true);
    }

    @Test
    void timeoutThrowsException(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("test.db");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            Future<Void> holder = executor.submit(() -> hold(db, true, held, release));
            assertTrue(held.await(2, TimeUnit.SECONDS));
            IndexLock.LockTimeoutException timeout = assertThrows(
                    IndexLock.LockTimeoutException.class, () -> IndexLock.forWrite(db, 100));
            assertTrue(timeout.getMessage().contains("timeout"));
            release.countDown();
            holder.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void zeroTimeoutMakesOneImmediateAttempt(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("test.db");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            Future<Void> holder = executor.submit(() -> hold(db, true, held, release));
            assertTrue(held.await(2, TimeUnit.SECONDS));
            assertThrows(IndexLock.LockTimeoutException.class, () -> IndexLock.forRead(db, 0));
            release.countDown();
            holder.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void negativeTimeoutIsRejected(@TempDir Path tmp) {
        assertThrows(IllegalArgumentException.class,
                () -> IndexLock.forWrite(tmp.resolve("test.db"), -1));
    }

    @Test
    void interruptionIsPropagated(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("test.db");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<Thread> waiterThread = new AtomicReference<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Void> holder = executor.submit(() -> hold(db, true, held, release));
            assertTrue(held.await(2, TimeUnit.SECONDS));
            Future<Boolean> waiter = executor.submit(() -> {
                waiterThread.set(Thread.currentThread());
                waiting.countDown();
                try (IndexLock ignored = IndexLock.forRead(db, 5_000)) {
                    return false;
                } catch (IndexLock.LockException interrupted) {
                    return Thread.currentThread().isInterrupted();
                }
            });
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            waiterThread.get().interrupt();
            assertTrue(waiter.get(2, TimeUnit.SECONDS),
                    "lock wait must restore the interrupted flag");
            release.countDown();
            holder.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void autoCloseableReleasesLock(@TempDir Path tmp) {
        Path db = tmp.resolve("test.db");
        try (IndexLock ignored = IndexLock.forWrite(db, 1000)) {
            // held until close
        }
        try (IndexLock lock = IndexLock.forWrite(db, 1000)) {
            assertNotNull(lock);
        }
    }

    @Test
    void lockPathDerivedFromDbPath(@TempDir Path tmp) {
        Path db = tmp.resolve("index.db");
        assertTrue(IndexLock.lockPathFor(db).equals(tmp.resolve("index.db.lock")));
    }

    private static void assertExcluded(Path db, boolean holderWrites,
                                       boolean contenderWrites) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Void> holder = executor.submit(() -> hold(db, holderWrites, held, release));
            assertTrue(held.await(2, TimeUnit.SECONDS));
            Future<Void> contender = executor.submit(() -> {
                try (IndexLock ignored = acquire(db, contenderWrites, 2_000)) {
                    acquired.countDown();
                }
                return null;
            });
            assertFalse(acquired.await(150, TimeUnit.MILLISECONDS),
                    "contender acquired while the incompatible lock was held");
            release.countDown();
            holder.get(2, TimeUnit.SECONDS);
            contender.get(2, TimeUnit.SECONDS);
            assertTrue(acquired.await(0, TimeUnit.MILLISECONDS));
        }
    }

    private static Void hold(Path db, boolean write, CountDownLatch held,
                             CountDownLatch release) throws Exception {
        try (IndexLock ignored = acquire(db, write, 2_000)) {
            held.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS), "test did not release held lock");
        }
        return null;
    }

    private static IndexLock acquire(Path db, boolean write, long timeoutMs) {
        return write ? IndexLock.forWrite(db, timeoutMs) : IndexLock.forRead(db, timeoutMs);
    }
}
