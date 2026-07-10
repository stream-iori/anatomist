package com.anatomist.core;

/** Result boundary between the index application service and a CLI adapter. */
public record IndexOutcome(int exitCode, String error, Throwable cause) {
    public static IndexOutcome success(int exitCode) {
        return new IndexOutcome(exitCode, null, null);
    }

    public static IndexOutcome failure(int exitCode, String error) {
        return new IndexOutcome(exitCode, error, null);
    }

    public static IndexOutcome failure(Throwable cause) {
        return new IndexOutcome(1, "index failed: " + cause.getMessage(), cause);
    }
}
