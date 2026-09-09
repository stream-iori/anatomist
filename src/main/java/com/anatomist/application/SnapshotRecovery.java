package com.anatomist.application;

import com.anatomist.store.SnapshotCatalog;
import com.anatomist.version.*;
import java.nio.file.*;
import java.util.*;

/** Caller holds the repository operation lock. Only exact owned paths are recovered. */
public final class SnapshotRecovery {
    private SnapshotRecovery() {}
    public static void claim(SnapshotService service) throws Exception {
        if(Files.exists(service.directory().resolve("workspace"),LinkOption.NOFOLLOW_LINKS))
            throw new SnapshotException("SNAPSHOT_WORKSPACE_UNOWNED","Managed workspace could not be safely recovered");
        Files.writeString(service.directory().resolve("workspace.owner"),service.git().commonDirectory()+"\n"+service.directory().resolve("workspace"));
    }
    public static List<Map<String,Object>> recover(SnapshotService service, SnapshotCatalog catalog, boolean execute) throws Exception {
        List<Map<String,Object>> actions=new ArrayList<>(SnapshotProcesses.recover(service.directory(),execute));
        Path workspace=service.directory().resolve("workspace"), marker=service.directory().resolve("workspace.owner");
        boolean registered=registered(service,workspace);
        boolean exists=Files.exists(workspace,LinkOption.NOFOLLOW_LINKS);
        boolean claimed=Files.isRegularFile(marker) && Files.readString(marker).equals(service.git().commonDirectory()+"\n"+workspace);
        // A legacy linked worktree at this exact managed path is also verifiable ownership.
        if(exists || registered) {
            boolean owned=claimed || registered;
            actions.add(Map.of("kind","workspace","path",workspace.toString(),"owned",owned));
            if(execute && owned) {
                if(registered) service.git().removeMaterialization(workspace);
                else SnapshotFiles.deleteOwned(service.directory(),workspace);
            }
        }
        for(var entry:catalog.list()) if(entry.status().equals("BUILDING") || entry.status().equals("DELETING")) {
            actions.add(Map.of("kind","snapshot","id",entry.id(),"status",entry.status()));
            if(execute) {
                try(var lock=com.anatomist.store.IndexLock.forWrite(service.database(entry.id()),0)) {
                    // Persist the recovery state before touching files; retries remain possible.
                    catalog.state(entry.id(),"DELETING");
                    deleteContents(service,entry.id());
                    catalog.delete(entry.id());
                } catch(com.anatomist.store.IndexLock.LockTimeoutException active) { /* retain active input */ }
            }
        }
        Path results=service.directory().resolve("results");
        if(Files.isDirectory(results)) try(var files=Files.list(results)) {
            for(Path owner:files.filter(p->p.getFileName().toString().matches("[a-f0-9]{32}\\.jsonl\\.owner")).toList()) {
                if(!Files.isRegularFile(owner,LinkOption.NOFOLLOW_LINKS) || !Files.readString(owner).equals("anatomist-diff-results-v1")) continue;
                Path data=owner.resolveSibling(owner.getFileName().toString().replace(".owner",""));
                try(var lease=com.anatomist.store.IndexLock.forWrite(data,0)) {
                    actions.add(Map.of("kind","result","path",data.toString(),"owned",true));
                    if(execute) { Files.deleteIfExists(data);Files.deleteIfExists(owner); }
                } catch(com.anatomist.store.IndexLock.LockTimeoutException active) { /* live query */ }
            }
        }
        return actions;
    }
    public static void deleteContents(SnapshotService service,String id) throws Exception {
        Path directory=service.snapshotDirectory(id);
        if(Files.isDirectory(directory)) try(var children=Files.list(directory)) {
            for(Path path:children.toList()) if(!path.getFileName().toString().endsWith(".lock")) SnapshotFiles.deleteOwned(service.directory(),path);
        }
    }
    private static Path canonical(Path path) {
        Path absolute=path.toAbsolutePath().normalize(),parent=absolute;
        while(parent!=null && !Files.exists(parent)) parent=parent.getParent();
        try { return parent==null?absolute:parent.toRealPath().resolve(parent.relativize(absolute)); }
        catch(java.io.IOException failure) { throw new SnapshotException("SNAPSHOT_PATH_INVALID","Cannot resolve workspace ownership",failure); }
    }
    private static boolean registered(SnapshotService service,Path path) {
        String listing=GitRepository.text(service.git().root(),"worktree","list","--porcelain","-z");
        for(String field:listing.split("\0")) if(field.startsWith("worktree ") && canonical(Path.of(field.substring(9))).equals(canonical(path))) return true;
        return false;
    }
}
