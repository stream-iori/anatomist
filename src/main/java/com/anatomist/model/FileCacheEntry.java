package com.anatomist.model;

public record FileCacheEntry(
        String sourceFile,
        String hash,
        int schemaVersion,
        String lastIndexed,
        int nodeCount,
        int edgeCount,
        int stale,
        String staleReason
) {
}
