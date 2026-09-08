package com.anatomist.application;

import com.anatomist.json.Json;
import com.anatomist.store.*;
import com.anatomist.version.*;
import java.nio.file.*;
import java.util.*;

/** Explicit, recoverable catalog-driven cleanup. Never deletes lock files or user worktrees. */
public final class SnapshotMaintenance {
    private SnapshotMaintenance() {}
    public static Map<String,Object> collect(SnapshotService service,int keep,boolean execute) {
        if(keep<0) throw new IllegalArgumentException("--keep must be >= 0");
        List<Map<String,Object>> candidates=new ArrayList<>(); Set<String> deleted=new HashSet<>();
        try(var operation=IndexOperationLock.forWrite(service.directory().resolve("catalog.db"));
            var catalog=new SnapshotCatalog(service.directory())) {
            List<SnapshotCatalog.Entry> entries=catalog.list();
            Set<String> protectedIds=new HashSet<>(catalog.protectedHeads());
            entries.stream().filter(e->e.status().equals("READY")).limit(keep).forEach(e->protectedIds.add(e.id()));
            for(var entry:entries) {
                if(entry.pinned() || protectedIds.contains(entry.id()) || entry.status().equals("BUILDING")) continue;
                Path db=service.database(entry.id());
                try(IndexLock write=IndexLock.forWrite(db,0)) {
                    long size=size(service.snapshotDirectory(entry.id()));
                    candidates.add(Map.of("id",entry.id(),"bytes",size));
                    if(execute) {
                        catalog.state(entry.id(),"DELETING");
                        // Keep the lock inode stable: a reader which already resolved this id may still wait on it.
                        if(Files.isDirectory(service.snapshotDirectory(entry.id()))) {
                            try(var children=Files.list(service.snapshotDirectory(entry.id()))) {
                                for(Path child:children.toList()) if(!child.getFileName().toString().endsWith(".lock"))
                                    SnapshotFiles.deleteOwned(service.directory(),child);
                            }
                        }
                        catalog.delete(entry.id());
                    }
                    deleted.add(entry.id());
                } catch(IndexLock.LockTimeoutException active) {
                    // Active readers/writers retain this snapshot and its source blobs.
                }
            }
            Set<String> retained=new HashSet<>();
            for(var entry:entries) {
                if(deleted.contains(entry.id())) continue;
                Path manifest=service.snapshotDirectory(entry.id()).resolve("files.json");
                if(!Files.isRegularFile(manifest)) {
                    if(entry.status().equals("READY")) throw new SnapshotException("SNAPSHOT_MANIFEST_MISSING","Cannot safely collect source cache: " + entry.id());
                    continue;
                }
                Object parsed=Json.parseTree(Files.readString(manifest));
                if(!(parsed instanceof Map<?,?> files)) throw new SnapshotException("SNAPSHOT_MANIFEST_INVALID","Invalid snapshot manifest");
                files.values().forEach(value->retained.add(String.valueOf(value)));
            }
            long blobBytes=new SourceBlobStore(service.directory().resolve("blobs")).collect(retained,execute);
            return Map.of("command","snapshots gc","execute",execute,"candidates",candidates,
                    "source_bytes",blobBytes,"deleted",execute?deleted.size():0,"recoverable",!execute);
        } catch(SnapshotException failure) { throw failure; }
        catch(Exception failure) { throw new SnapshotException("SNAPSHOT_GC_FAILED",failure.getMessage(),failure); }
    }
    public static long size(Path root) throws java.io.IOException {
        if(!Files.exists(root)) return 0;
        try(var files=Files.walk(root)) {
            long total=0;for(Path file:files.filter(Files::isRegularFile).toList()) total+=Files.size(file);return total;
        }
    }
}
