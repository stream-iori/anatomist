package com.anatomist.version;

/** Stable failures shared by version management and its CLI adapters. */
public final class SnapshotException extends IllegalStateException {
    private final String code;
    private final java.util.Map<String,Object> details;
    public SnapshotException(String code, String message) { this(code,message,java.util.Map.of()); }
    public SnapshotException(String code, String message, Throwable cause) {
        super(message, cause); this.code = code; this.details=java.util.Map.of();
    }
    public SnapshotException(String code, String message, java.util.Map<String,Object> details) {
        super(message); this.code=code; this.details=java.util.Map.copyOf(details);
    }
    public String code() { return code; }
    public java.util.Map<String,Object> details() { return details; }
}
