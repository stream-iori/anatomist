package com.anatomist.store;

import java.nio.file.Path;

/**
 * Serializes index writers without blocking queries.  The normal {@link IndexLock}
 * protects the live SQLite file; this lock protects long-running work which may
 * be writing a replacement database beside it.
 */
public final class IndexOperationLock implements AutoCloseable {

    private final IndexLock delegate;

    private IndexOperationLock(IndexLock delegate) {
        this.delegate = delegate;
    }

    public static IndexOperationLock forWrite(Path indexPath) {
        Path operationPath = indexPath.resolveSibling(indexPath.getFileName() + ".operation");
        return new IndexOperationLock(IndexLock.forWrite(operationPath));
    }

    public static IndexOperationLock tryForWrite(Path indexPath, long timeoutMs) {
        Path operationPath = indexPath.resolveSibling(indexPath.getFileName() + ".operation");
        return new IndexOperationLock(IndexLock.forWrite(operationPath, timeoutMs));
    }

    @Override
    public void close() {
        delegate.close();
    }
}
