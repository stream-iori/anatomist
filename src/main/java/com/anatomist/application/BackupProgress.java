package com.anatomist.application;

import java.io.PrintStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.sqlite.core.DB;

/** One-line stderr progress; the timer never touches the connection held by native backup. */
final class BackupProgress implements DB.ProgressObserver, AutoCloseable {
    private final PrintStream output;
    private final long started = System.nanoTime();
    private final ScheduledExecutorService timer;
    private int copiedPages;
    private int totalPages;
    private boolean finished;

    BackupProgress(PrintStream output) {
        this(output, 2_000);
    }

    BackupProgress(PrintStream output, long intervalMillis) {
        if (intervalMillis <= 0) throw new IllegalArgumentException("interval must be positive");
        this.output = output;
        timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "anatomist-backup-progress");
            thread.setDaemon(true);
            return thread;
        });
        emit("started");
        timer.scheduleWithFixedDelay(this::heartbeat, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void progress(int remaining, int pageCount) {
        if (!finished && pageCount > 0 && remaining >= 0 && remaining <= pageCount) {
            totalPages = pageCount;
            copiedPages = pageCount - remaining;
        }
    }

    synchronized void heartbeat() {
        if (!finished) emit("running");
    }

    synchronized void complete() {
        finish("completed");
    }

    @Override
    public synchronized void close() {
        finish("failed");
    }

    private void finish(String status) {
        if (finished) return;
        finished = true;
        timer.shutdownNow();
        if (status.equals("completed")) copiedPages = totalPages;
        emit(status);
    }

    private void emit(String status) {
        StringBuilder line = new StringBuilder("[anatomist-progress] phase=sqlite_backup status=").append(status);
        if (status.equals("completed") || totalPages > 0) {
            long percent = status.equals("completed") ? 100 : copiedPages * 100L / totalPages;
            line.append(" percent=").append(percent);
        }
        if (totalPages > 0) line.append(" copied_pages=").append(copiedPages).append(" total_pages=").append(totalPages);
        line.append(" elapsed_ms=").append((System.nanoTime() - started) / 1_000_000);
        output.println(line.toString());
        output.flush();
    }
}
