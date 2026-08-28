package com.anatomist.config;

import java.nio.file.Path;

/** Fail-closed configuration error reported as a CLI usage failure. */
public final class ConfigException extends RuntimeException {
    private final Path file;
    private final int line;

    public ConfigException(Path file, int line, String message) {
        super(format(file, line, message));
        this.file = file;
        this.line = line;
    }

    public ConfigException(Path file, String message, Throwable cause) {
        super(format(file, 0, message), cause);
        this.file = file;
        this.line = 0;
    }

    public Path file() { return file; }
    public int line() { return line; }

    private static String format(Path file, int line, String message) {
        String location = file == null ? "config.toml" : file.toString();
        if (line > 0) location += ":" + line;
        return location + ": " + message;
    }
}
