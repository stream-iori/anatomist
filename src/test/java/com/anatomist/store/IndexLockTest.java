package com.anatomist.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class IndexLockTest {

    @Test
    void readLocksCanBeConcurrent(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("test.db");
        CountDownLatch bothHeld = new CountDownLatch(2);
        AtomicBoolean success = new AtomicBoolean(true);

        Thread t1 = new Thread(() -> {
            try (IndexLock lock = IndexLock.forRead(db, 2000)) {
                bothHeld.countDown();
                assertTrue(bothHeld.await(2, TimeUnit.SECONDS));
            } catch (Exception e) {
                success.set(false);
            }
        });
        Thread t2 = new Thread(() -> {
            try (IndexLock lock = IndexLock.forRead(db, 2000)) {
                bothHeld.countDown();
                assertTrue(bothHeld.await(2, TimeUnit.SECONDS));
            } catch (Exception e) {
                success.set(false);
            }
        });

        t1.start();
        t2.start();
        t1.join(5000);
        t2.join(5000);
        assertTrue(success.get(), "Both read locks should be held concurrently");
    }

    @Test
    void writeLockExcludesRead(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("test.db");
        CountDownLatch writerHeld = new CountDownLatch(1);
        CountDownLatch writerDone = new CountDownLatch(1);
        AtomicBoolean readerAcquiredDuringWrite = new AtomicBoolean(false);

        Thread writer = new Thread(() -> {
            try (IndexLock lock = IndexLock.forWrite(db, 2000)) {
                writerHeld.countDown();
                Thread.sleep(300);
            } catch (Exception e) {
                // ignore
            } finally {
                writerDone.countDown();
            }
        });

        Thread reader = new Thread(() -> {
            try {
                writerHeld.await(2, TimeUnit.SECONDS);
                Thread.sleep(50); // ensure writer holds lock
                long before = System.currentTimeMillis();
                try (IndexLock lock = IndexLock.forRead(db, 5000)) {
                    long elapsed = System.currentTimeMillis() - before;
                    if (elapsed < 200) {
                        readerAcquiredDuringWrite.set(true);
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        });

        writer.start();
        reader.start();
        writer.join(5000);
        reader.join(5000);
        assertFalse(readerAcquiredDuringWrite.get(),
                "Reader should not acquire lock while writer holds it");
    }

    @Test
    void writeLockExcludesWrite(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("test.db");
        CountDownLatch firstHeld = new CountDownLatch(1);
        AtomicBoolean secondAcquiredDuringFirst = new AtomicBoolean(false);

        Thread first = new Thread(() -> {
            try (IndexLock lock = IndexLock.forWrite(db, 2000)) {
                firstHeld.countDown();
                Thread.sleep(300);
            } catch (Exception e) {
                // ignore
            }
        });

        Thread second = new Thread(() -> {
            try {
                firstHeld.await(2, TimeUnit.SECONDS);
                Thread.sleep(50);
                long before = System.currentTimeMillis();
                try (IndexLock lock = IndexLock.forWrite(db, 5000)) {
                    long elapsed = System.currentTimeMillis() - before;
                    if (elapsed < 200) {
                        secondAcquiredDuringFirst.set(true);
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        });

        first.start();
        second.start();
        first.join(5000);
        second.join(5000);
        assertFalse(secondAcquiredDuringFirst.get(),
                "Second writer should wait for first to release");
    }

    @Test
    void readLockExcludesWrite(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("test.db");
        CountDownLatch readerHeld = new CountDownLatch(1);
        AtomicBoolean writerAcquiredDuringRead = new AtomicBoolean(false);

        Thread reader = new Thread(() -> {
            try (IndexLock lock = IndexLock.forRead(db, 2000)) {
                readerHeld.countDown();
                Thread.sleep(300);
            } catch (Exception e) {
                // ignore
            }
        });

        Thread writer = new Thread(() -> {
            try {
                readerHeld.await(2, TimeUnit.SECONDS);
                Thread.sleep(50);
                long before = System.currentTimeMillis();
                try (IndexLock lock = IndexLock.forWrite(db, 5000)) {
                    long elapsed = System.currentTimeMillis() - before;
                    if (elapsed < 200) {
                        writerAcquiredDuringRead.set(true);
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        });

        reader.start();
        writer.start();
        reader.join(5000);
        writer.join(5000);
        assertFalse(writerAcquiredDuringRead.get(),
                "Writer should wait for reader to release");
    }

    @Test
    void timeoutThrowsException(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("test.db");
        CountDownLatch held = new CountDownLatch(1);
        AtomicReference<Exception> caught = new AtomicReference<>();

        Thread holder = new Thread(() -> {
            try (IndexLock lock = IndexLock.forWrite(db, 2000)) {
                held.countDown();
                Thread.sleep(2000);
            } catch (Exception e) {
                // ignore
            }
        });

        holder.start();
        held.await(2, TimeUnit.SECONDS);
        Thread.sleep(50);

        try {
            IndexLock.forWrite(db, 200);
            fail("Should have thrown LockTimeoutException");
        } catch (IndexLock.LockTimeoutException e) {
            assertTrue(e.getMessage().contains("timeout"));
        } finally {
            holder.interrupt();
            holder.join(3000);
        }
    }

    @Test
    void autoCloseableReleasesLock(@TempDir Path tmp) {
        Path db = tmp.resolve("test.db");
        try (IndexLock lock = IndexLock.forWrite(db, 1000)) {
            // hold lock
        }
        // After close, should be able to acquire again
        try (IndexLock lock = IndexLock.forWrite(db, 1000)) {
            assertNotNull(lock);
        }
    }

    @Test
    void lockPathDerivedFromDbPath(@TempDir Path tmp) {
        Path db = tmp.resolve("index.db");
        Path lockPath = IndexLock.lockPathFor(db);
        assertEquals(tmp.resolve("index.db.lock"), lockPath);
    }
}
