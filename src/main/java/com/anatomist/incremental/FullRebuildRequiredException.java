package com.anatomist.incremental;

/** Signals that a watch worker must schedule a safe full replacement instead
 * of running a synchronous full index against the active database. */
public final class FullRebuildRequiredException extends RuntimeException {
    public FullRebuildRequiredException(String reason) {
        super(reason);
    }
}
