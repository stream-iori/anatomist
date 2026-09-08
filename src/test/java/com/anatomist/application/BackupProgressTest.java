package com.anatomist.application;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BackupProgressTest {
    @TempDir Path temporary;

    private static final class Output extends PrintStream {
        final List<String> lines = new CopyOnWriteArrayList<>();
        final CountDownLatch running = new CountDownLatch(1);
        Output() { super(OutputStream.nullOutputStream()); }
        @Override public void println(String line) {
            lines.add(line);
            if (line.contains("status=running")) running.countDown();
        }
    }

    @Test void fastCopyOnlyEmitsStartAndCompletion() {
        Output out = new Output();
        try (BackupProgress progress = new BackupProgress(out, 60_000)) {
            progress.progress(58, 100);
            assertEquals(1, out.lines.size(), "callbacks do not flood stderr");
            progress.complete();
            progress.heartbeat();
            progress.progress(0, 100);
        }
        assertEquals(2, out.lines.size());
        assertTrue(out.lines.getFirst().contains("status=started"));
        assertTrue(out.lines.getLast().contains("status=completed percent=100 copied_pages=100 total_pages=100"));
        assertTrue(out.lines.stream().allMatch(line -> line.startsWith("[anatomist-progress] phase=sqlite_backup ")
                && line.matches(".* elapsed_ms=\\d+") && !line.contains("\r") && !line.contains("\u001b")));
    }

    @Test void heartbeatReportsUnknownOrUnchangedProgressWithoutInventingIt() {
        Output out = new Output();
        try (BackupProgress progress = new BackupProgress(out, 60_000)) {
            progress.heartbeat();
            assertFalse(out.lines.getLast().contains("percent="));
            progress.progress(58, 100);
            progress.heartbeat();
            progress.heartbeat();
            assertTrue(out.lines.get(2).contains("percent=42 copied_pages=42 total_pages=100"));
            assertTrue(out.lines.get(3).contains("percent=42 copied_pages=42 total_pages=100"));
            progress.progress(-1, 0);
            progress.heartbeat();
            assertTrue(out.lines.getLast().contains("percent=42"));
        }
        assertTrue(out.lines.getLast().contains("status=failed"));
    }

    @Test void timerEmitsHeartbeatWithoutAnyCallbackAndStopsOnClose() throws Exception {
        Output out = new Output();
        BackupProgress progress = new BackupProgress(out, 20);
        try {
            assertTrue(out.running.await(5, TimeUnit.SECONDS), "heartbeat must not depend on SQLite callbacks");
        } finally { progress.close(); }
        int count = out.lines.size();
        progress.heartbeat();
        progress.complete();
        progress.close();
        assertEquals(count, out.lines.size());
        assertTrue(out.lines.getLast().contains("status=failed"));
    }

    @Test void hundredPercentCallbackIsNotSuccess() {
        Output out = new Output();
        assertThrows(IllegalStateException.class, () -> {
            try (BackupProgress progress = new BackupProgress(out, 60_000)) {
                progress.progress(0, 100);
                throw new IllegalStateException("backup failed after last callback");
            }
        });
        assertTrue(out.lines.getLast().contains("status=failed percent=100"));
        assertTrue(out.lines.stream().noneMatch(line -> line.contains("status=completed")));
    }

    @Test void actualSqliteBackupReportsPagesAndPreservesData() throws Exception {
        Output out = new Output();
        Path copy = temporary.resolve("copy.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sample(data BLOB)");
            statement.execute("WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<300) "
                    + "INSERT INTO sample SELECT randomblob(4096) FROM n");
            try (BackupProgress progress = new BackupProgress(out, 60_000)) {
                assertEquals(0, ((org.sqlite.SQLiteConnection) connection).getDatabase().backup("main", copy.toString(), progress));
                progress.complete();
            }
        }
        assertTrue(out.lines.getLast().matches(".*status=completed percent=100 copied_pages=[1-9]\\d* total_pages=[1-9]\\d* elapsed_ms=\\d+"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + copy);
             var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM sample")) {
            assertTrue(rows.next());
            assertEquals(300, rows.getInt(1));
        }
    }

    @Test void actualSqliteFailureEmitsFailureAndKeepsResultCode() throws Exception {
        Output out = new Output();
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (BackupProgress progress = new BackupProgress(out, 60_000)) {
                int result = ((org.sqlite.SQLiteConnection) connection).getDatabase().backup("main", temporary.toString(), progress);
                assertNotEquals(0, result, "the caller must retain SQLite's failure result");
            }
        }
        assertEquals(2, out.lines.size());
        assertTrue(out.lines.getLast().contains("status=failed"));
    }
}
