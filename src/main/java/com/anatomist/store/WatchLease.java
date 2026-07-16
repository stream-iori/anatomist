package com.anatomist.store;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/** One auto-index watch process owns an index event stream at a time. */
public final class WatchLease implements AutoCloseable {
    private static final ConcurrentHashMap<String, Boolean> LOCAL_OWNERS = new ConcurrentHashMap<>();

    private final IndexLock lock;
    private final String ownerKey;

    private WatchLease(IndexLock lock, String ownerKey) {
        this.lock = lock;
        this.ownerKey = ownerKey;
    }

    public static WatchLease acquire(Path indexPath) {
        Path normalized = indexPath.toAbsolutePath().normalize();
        String ownerKey = normalized.toString();
        if (LOCAL_OWNERS.putIfAbsent(ownerKey, Boolean.TRUE) != null) {
            throw new IndexLock.LockTimeoutException("WATCH_ALREADY_RUNNING for " + normalized);
        }
        try {
            Path leasePath = normalized.resolveSibling(normalized.getFileName() + ".watch");
            return new WatchLease(IndexLock.forWrite(leasePath, 250), ownerKey);
        } catch (RuntimeException ex) {
            LOCAL_OWNERS.remove(ownerKey);
            throw ex;
        }
    }

    @Override
    public void close() {
        try {
            lock.close();
        } finally {
            LOCAL_OWNERS.remove(ownerKey);
        }
    }
}
