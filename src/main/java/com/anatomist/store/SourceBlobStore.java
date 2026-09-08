package com.anatomist.store;

import com.anatomist.version.SnapshotException;
import com.anatomist.version.SnapshotFiles;

import com.anatomist.store.FileCacheService;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Immutable content-addressed source cache. Repository operation lock protects publication/GC. */
public final class SourceBlobStore {
    private final Path directory;
    public SourceBlobStore(Path directory) { this.directory=directory; }
    public Path path(String hash) {
        if(!hash.matches("[a-f0-9]{64}")) throw new SnapshotException("SNAPSHOT_HASH_INVALID","Invalid source hash");
        return directory.resolve(hash);
    }
    public record Stats(long writtenBytes,int writtenFiles,int reusedFiles) {}
    public Stats store(Path root,Map<String,String> files) throws IOException {
        Files.createDirectories(directory);long bytes=0;int written=0,reused=0;
        for(var file:files.entrySet()) {
            Path destination=path(file.getValue());
            if(Files.exists(destination)) {
                if(!file.getValue().equals(FileCacheService.sha256(destination)))
                    throw new SnapshotException("SNAPSHOT_SOURCE_CORRUPT","Source cache content mismatch: " + file.getValue());
                reused++;continue;
            }
            Path temporary=Files.createTempFile(directory,".capture-",".tmp");
            try {
                Files.copy(SnapshotFiles.resolve(root,file.getKey()),temporary,StandardCopyOption.REPLACE_EXISTING);
                if(!file.getValue().equals(FileCacheService.sha256(temporary)))
                    throw new SnapshotException("SNAPSHOT_INPUT_CHANGED","Source changed before caching: " + file.getKey());
                bytes+=Files.size(temporary);
                try { Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE); }
                catch(AtomicMoveNotSupportedException failure) { Files.move(temporary,destination); }
                written++;
            } finally { Files.deleteIfExists(temporary); }
        }
        return new Stats(bytes,written,reused);
    }
    public long collect(Set<String> retained,boolean execute) throws IOException {
        if(!Files.isDirectory(directory)) return 0;
        long bytes=0;
        try(var stream=Files.list(directory)) {
            for(Path file:stream.toList()) {
                String name=file.getFileName().toString();
                if((name.matches("[a-f0-9]{64}") && !retained.contains(name)) || name.startsWith(".capture-")) {
                    bytes+=Files.size(file); if(execute) Files.delete(file);
                }
            }
        } return bytes;
    }
}
