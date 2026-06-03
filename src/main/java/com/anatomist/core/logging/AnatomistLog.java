package com.anatomist.core.logging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Minimal, dependency-free file logger. Native-image safe (plain NIO, no
 * reflection, no service discovery).
 *
 * <p>Two sinks, each capped at {@value #MAX_BYTES} bytes with one rolling
 * backup ({@code <name>.1}):</p>
 * <ul>
 *   <li>{@code warn(..)}  → {@code <repoDir>/warn.log}  (per-project)</li>
 *   <li>{@code debug(..)} → {@code <home>/logs/debug.log} (global)</li>
 * </ul>
 *
 * <p>Until {@link #configure} runs, {@code warn} falls back to stderr and
 * {@code debug} is dropped. Logging never throws — a failed write is swallowed
 * so it can't break an index run.</p>
 */
public final class AnatomistLog {

    /** Per-file cap before rolling to {@code <name>.1}. */
    public static final long MAX_BYTES = 1L << 30; // 1 GiB

    private static volatile Sink warnSink;
    private static volatile Sink debugSink;
    private static volatile boolean debugEnabled;

    private AnatomistLog() {}

    /** Point the sinks at a concrete project. Safe to call more than once.
     *  Debug logging stays off (the {@code debug.log} sink is wired but inert)
     *  unless {@link #configure(Path, Path, boolean)} turns it on. */
    public static void configure(Path repoDir, Path home) {
        configure(repoDir, home, false);
    }

    /** As {@link #configure(Path, Path)}, but {@code debug} gates whether
     *  {@link #debug(String)} actually writes. Off by default so a normal index
     *  run produces no debug.log churn. */
    public static void configure(Path repoDir, Path home, boolean debug) {
        warnSink = new Sink(repoDir.resolve("warn.log"), MAX_BYTES);
        debugSink = new Sink(home.resolve("logs").resolve("debug.log"), MAX_BYTES);
        debugEnabled = debug;
    }

    /** Cheap guard so hot paths can skip building a debug message string. */
    public static boolean isDebugEnabled() {
        return debugEnabled && debugSink != null;
    }

    public static void warn(String message) {
        Sink s = warnSink;
        if (s != null) s.write("WARN", message);
        else System.err.println("WARN: " + message);
    }

    public static void debug(String message) {
        if (!debugEnabled) return;
        Sink s = debugSink;
        if (s != null) s.write("DEBUG", message);
        // No console fallback for debug — it's opt-in via --debug, file only.
    }

    /** Append-only file with a single-backup size cap. */
    private static final class Sink {
        private final Path file;
        private final long maxBytes;

        Sink(Path file, long maxBytes) {
            this.file = file;
            this.maxBytes = maxBytes;
        }

        synchronized void write(String level, String message) {
            try {
                Path parent = file.getParent();
                if (parent != null) Files.createDirectories(parent);
                rollIfNeeded();
                String line = Instant.now() + " " + level + " " + message
                        + System.lineSeparator();
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException ignore) {
                // A logging failure must never abort indexing.
            }
        }

        private void rollIfNeeded() throws IOException {
            if (Files.exists(file) && Files.size(file) >= maxBytes) {
                Path backup = file.resolveSibling(file.getFileName() + ".1");
                Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
