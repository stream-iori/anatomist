package com.anatomist.version;

/** Stable failures shared by version management and its CLI adapters. */
public final class SnapshotException extends IllegalStateException {
    private final String code;
    public SnapshotException(String code, String message) { super(message); this.code = code; }
    public SnapshotException(String code, String message, Throwable cause) {
        super(message, cause); this.code = code;
    }
    public String code() { return code; }
}
