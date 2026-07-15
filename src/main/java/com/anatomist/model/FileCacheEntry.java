package com.anatomist.model;

public record FileCacheEntry(
        String sourceFile,
        String hash,
        int schemaVersion,
        String lastIndexed,
        int nodeCount,
        int edgeCount,
        long fileSize,
        long fileMtimeNs,
        String contractHash
) {
    public FileCacheEntry(String sourceFile, String hash, int schemaVersion,
                          String lastIndexed, int nodeCount, int edgeCount) {
        this(sourceFile, hash, schemaVersion, lastIndexed, nodeCount, edgeCount,
                -1L, -1L, "");
    }
}
