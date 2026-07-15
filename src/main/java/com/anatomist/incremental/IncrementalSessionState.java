package com.anatomist.incremental;

import com.anatomist.store.SqliteStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Watch-owned mutable state committed only after a successful graph promotion. */
public final class IncrementalSessionState implements AutoCloseable {
    private Set<String> knownNodeIds;
    private Path stagingPath;
    private Path indexPath;

    public synchronized Set<String> knownNodeIds(SqliteStore store) {
        if (knownNodeIds == null) knownNodeIds = new HashSet<>(store.allNodeIds());
        return new HashSet<>(knownNodeIds);
    }

    public synchronized void replaceKnownNodeIds(Set<String> ids) {
        knownNodeIds = ids == null ? null : new HashSet<>(ids);
    }

    public synchronized void invalidateKnownNodeIds() {
        knownNodeIds = null;
    }

    public synchronized Path stagingPath(Path targetIndex) {
        Path normalized = targetIndex.toAbsolutePath().normalize();
        if (!normalized.equals(indexPath)) {
            deleteStaging();
            indexPath = normalized;
            stagingPath = normalized.resolveSibling(normalized.getFileName()
                    + ".stage-watch-" + ProcessHandle.current().pid() + ".db");
        }
        return stagingPath;
    }

    @Override
    public synchronized void close() {
        knownNodeIds = null;
        deleteStaging();
    }

    private void deleteStaging() {
        if (stagingPath != null) {
            try { Files.deleteIfExists(stagingPath); } catch (Exception ignored) {}
        }
        stagingPath = null;
    }
}
