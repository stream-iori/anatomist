package com.anatomist.core.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AnatomistLogTest {

    @Test
    void debug_isInertUntilEnabled(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        Path home = tmp.resolve("home");
        AnatomistLog.configure(repo, home); // debug defaults off

        assertFalse(AnatomistLog.isDebugEnabled());
        AnatomistLog.debug("should not be written");

        Path debugLog = home.resolve("logs").resolve("debug.log");
        assertFalse(Files.exists(debugLog), "debug.log must not be created when debug is off");
    }

    @Test
    void debug_writesWhenEnabled(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        Path home = tmp.resolve("home");
        AnatomistLog.configure(repo, home, true);

        assertTrue(AnatomistLog.isDebugEnabled());
        AnatomistLog.debug("hello-debug");

        Path debugLog = home.resolve("logs").resolve("debug.log");
        assertTrue(Files.exists(debugLog), "debug.log should be created when debug is on");
        String content = Files.readString(debugLog);
        assertTrue(content.contains("DEBUG"), "expected DEBUG level; got: " + content);
        assertTrue(content.contains("hello-debug"), "expected message; got: " + content);
    }

    @Test
    void warn_alwaysWritesRegardlessOfDebug(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        Path home = tmp.resolve("home");
        AnatomistLog.configure(repo, home, false);

        AnatomistLog.warn("a-warning");

        Path warnLog = repo.resolve("warn.log");
        assertTrue(Files.exists(warnLog));
        assertTrue(Files.readString(warnLog).contains("a-warning"));
    }
}
