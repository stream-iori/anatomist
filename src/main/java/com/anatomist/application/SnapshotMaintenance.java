package com.anatomist.application;

import com.anatomist.config.ConfigLoader;
import com.anatomist.json.Json;
import com.anatomist.store.*;
import com.anatomist.version.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Explicit retention policy; every deletion is catalog/ownership driven and reader protected. */
public final class SnapshotMaintenance {
    private SnapshotMaintenance() {}
    public static void autoCollect(SnapshotService service,Set<String> endpoints) {
        var config=ConfigLoader.load(service.git().project());
        if(!config.versionsAutoGc()) return;
        try { collect(service,config.versionsKeep(),config.versionsMaxBytes(),config.versionsMaxAgeDays(),config.versionsIncludeCaches(),true,endpoints); }
        catch(RuntimeException failure) {
            System.err.println(Json.writeCompact(Map.of("record","warning","code","SNAPSHOT_GC_PENDING","message",failure.getMessage())));
        }
    }
    public static Map<String,Object> collect(SnapshotService service,int keep,boolean execute) {
        return collect(service,keep,0,30,false,execute,Set.of());
    }
    public static Map<String,Object> collect(SnapshotService service,int keep,long maxBytes,int maxAgeDays,boolean caches,boolean execute,Set<String> endpoints) {
        if(keep<0 || maxBytes<0 || maxAgeDays<0) throw new IllegalArgumentException("Retention values must be >= 0");
        try(var operation=IndexOperationLock.forWrite(service.directory().resolve("catalog.db"));
            var catalog=new SnapshotCatalog(service.directory())) {
            var recovery=SnapshotRecovery.recover(service,catalog,execute);
            var entries=catalog.list();
            Map<String,Set<String>> manifests=new HashMap<>(); Map<String,Integer> references=new HashMap<>();
            for(var entry:entries) {
                Set<String> hashes=manifest(service,entry);
                manifests.put(entry.id(),hashes); hashes.forEach(hash->references.merge(hash,1,Integer::sum));
            }
            Set<String> protectedIds=new HashSet<>(endpoints);
            List<Map<String,Object>> protection=new ArrayList<>(), expired=new ArrayList<>(), candidates=new ArrayList<>();
            Set<String> checkouts=new HashSet<>(); Map<String,Path> checkoutPaths=new HashMap<>();
            for(String field:GitRepository.text(service.git().root(),"worktree","list","--porcelain","-z").split("\0")) {
                if(field.startsWith("worktree ")) {
                    Path path=Path.of(field.substring(9));
                    if(Files.isDirectory(path)) {
                        path=path.toRealPath();String key=FileCacheService.sha256OfString(path.toString()); checkouts.add(key);checkoutPaths.put(key,path);
                    }
                }
            }
            Instant cutoff=Instant.now().minusSeconds((long)maxAgeDays*86400);
            for(var head:catalog.headEntries()) {
                boolean valid=head.selected()!=null && !Instant.parse(head.selected()).isBefore(cutoff);
                var entry=catalog.get(head.id());
                String selector=head.selector();
                if(selector.startsWith("WORKTREE:") || selector.startsWith("HEAD:")) {
                    String checkout=selector.substring(selector.indexOf(':')+1);
                    valid &= checkouts.contains(checkout);
                    if(valid && selector.startsWith("HEAD:")) valid=GitRepository.text(checkoutPaths.get(checkout),"rev-parse","HEAD").equals(entry.commit());
                } else {
                    try { valid &= service.git().commit(selector).equals(entry.commit()); }
                    catch(SnapshotException missing) { valid=false; }
                }
                if(valid) {
                    protectedIds.add(head.id()); protection.add(Map.of("id",head.id(),"reason","entrypoint","selector",selector));
                } else {
                    expired.add(Map.of("selector",selector,"id",head.id())); if(execute) catalog.expire(head);
                }
            }
            for(var entry:entries) if(entry.pinned()) { protectedIds.add(entry.id());protection.add(Map.of("id",entry.id(),"reason","pin")); }
            endpoints.forEach(id->protection.add(Map.of("id",id,"reason","current_operation")));
            Set<String> newest=new HashSet<>();entries.stream().filter(e->e.status().equals("READY")).limit(keep).forEach(e->newest.add(e.id()));
            long projected=size(service.directory())-new SourceBlobStore(service.directory().resolve("blobs")).collect(references.keySet(),false),sourceBytes=0;
            if(!execute) for(var action:recovery) if("workspace".equals(action.get("kind")) && Boolean.TRUE.equals(action.get("owned")))
                projected-=size(service.directory().resolve("workspace"));
            Set<String> deleted=new HashSet<>();
            var oldest=new ArrayList<>(entries);Collections.reverse(oldest);
            for(var entry:oldest) {
                if(protectedIds.contains(entry.id()) || entry.status().equals("BUILDING")) continue;
                if(newest.contains(entry.id()) && (maxBytes==0 || projected<=maxBytes)) continue;
                try(IndexLock write=IndexLock.forWrite(service.database(entry.id()),0)) {
                    long bytes=size(service.snapshotDirectory(entry.id()));
                    candidates.add(Map.of("id",entry.id(),"bytes",bytes)); projected-=bytes;
                    for(String hash:manifests.get(entry.id())) if(references.merge(hash,-1,Integer::sum)==0) {
                        Path blob=new SourceBlobStore(service.directory().resolve("blobs")).path(hash);
                        if(Files.isRegularFile(blob)) projected-=Files.size(blob);
                    }
                    if(execute) {
                        catalog.state(entry.id(),"DELETING");SnapshotRecovery.deleteContents(service,entry.id());catalog.delete(entry.id());
                    }
                    deleted.add(entry.id());
                } catch(IndexLock.LockTimeoutException active) { protection.add(Map.of("id",entry.id(),"reason","active_reader")); }
            }
            Set<String> retained=new HashSet<>();references.forEach((hash,count)->{if(count>0) retained.add(hash);});
            sourceBytes=new SourceBlobStore(service.directory().resolve("blobs")).collect(retained,execute);
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("command","snapshots gc");result.put("execute",execute);result.put("candidates",candidates);
            result.put("source_bytes",sourceBytes);result.put("deleted",execute?deleted.size():0);result.put("recoverable",!execute);
            result.put("recovery",recovery);result.put("protected",protection);result.put("expired_entrypoints",expired);
            result.put("max_bytes",maxBytes);result.put("budget_met",maxBytes==0 || (execute?size(service.directory()):Math.max(0,projected))<=maxBytes);
            if(caches) result.put("caches",ClasspathCacheMaintenance.collect(maxAgeDays,execute));
            result.put("storage",stats(service));return result;
        } catch(SnapshotException failure) { throw failure; }
        catch(Exception failure) { throw new SnapshotException("SNAPSHOT_GC_FAILED",failure.getMessage(),failure); }
    }
    private static Set<String> manifest(SnapshotService service,SnapshotCatalog.Entry entry) throws Exception {
        Path path=service.snapshotDirectory(entry.id()).resolve("files.json");
        if(!Files.isRegularFile(path)) {
            if(entry.status().equals("READY")) throw new SnapshotException("SNAPSHOT_MANIFEST_MISSING","Cannot safely collect source cache: "+entry.id());
            return Set.of();
        }
        if(!(Json.parseTree(Files.readString(path)) instanceof Map<?,?> files)) throw new SnapshotException("SNAPSHOT_MANIFEST_INVALID","Invalid snapshot manifest");
        Set<String> result=new HashSet<>();
        for(Object value:files.values()) {
            String hash=String.valueOf(value);new SourceBlobStore(service.directory().resolve("blobs")).path(hash);result.add(hash);
        }
        return result;
    }
    public static Map<String,Object> stats(SnapshotService service) throws java.io.IOException {
        Map<String,Long> bytes=new TreeMap<>(),counts=new TreeMap<>();
        Set<String> known=new HashSet<>();Path catalogPath=service.directory().resolve("catalog.db");
        if(Files.isRegularFile(catalogPath)) try(var catalog=SnapshotCatalog.read(service.directory())) { catalog.list().forEach(e->known.add(e.id())); }
        walkFiles(service.directory(), (file, attributes) -> {
                String rel=SnapshotFiles.relative(service.directory(),file),name=file.getFileName().toString();
                String kind=name.endsWith(".lock")?"locks":rel.startsWith("workspace/")?"workspace":rel.startsWith("results/")?"results":rel.startsWith("blobs/")?"blobs":rel.startsWith("snapshots/")?"snapshots":"catalog";
                if(kind.equals("snapshots") && !known.contains(rel.split("/")[1])) kind="unowned";
                bytes.merge(kind,attributes.size(),Long::sum);counts.merge(kind,1L,Long::sum);
        });
        return Map.of("command","snapshots stats","directory",service.directory().toString(),"bytes",bytes,"files",counts,"total_bytes",bytes.values().stream().mapToLong(Long::longValue).sum());
    }
    public static long size(Path root) throws java.io.IOException {
        long[] total={0};walkFiles(root,(file,attributes)->total[0]+=attributes.size());return total[0];
    }
    private static void walkFiles(Path root,java.util.function.BiConsumer<Path,java.nio.file.attribute.BasicFileAttributes> visit) throws java.io.IOException {
        Files.walkFileTree(root,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attributes) {
                if(attributes.isRegularFile()) visit.accept(file,attributes);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file,java.io.IOException failure) throws java.io.IOException {
                // A query can release a result spool while a concurrent GC counts storage.
                if(failure instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
                throw failure;
            }
        });
    }
}
