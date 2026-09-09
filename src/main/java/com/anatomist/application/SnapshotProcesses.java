package com.anatomist.application;

import com.anatomist.json.Json;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Receipts identify private build subprocesses even if their Anatomist parent is killed. */
public final class SnapshotProcesses implements AutoCloseable {
    private static final ThreadLocal<Path> OWNER=new ThreadLocal<>();
    private final Path previous;
    private SnapshotProcesses(Path directory) { previous=OWNER.get();OWNER.set(directory); }
    public static SnapshotProcesses enter(Path directory) { return new SnapshotProcesses(directory); }
    @Override public void close() { if(previous==null) OWNER.remove();else OWNER.set(previous); }
    public static AutoCloseable watch(Process process) throws java.io.IOException {
        Path owner=OWNER.get();
        if(owner==null) return ()->{if(process.isAlive()) stop(process.toHandle());};
        Path directory=Files.createDirectories(owner.resolve("processes"));
        Path receipt=directory.resolve(UUID.randomUUID().toString().replace("-","")+".json");
        Path pending=receipt.resolveSibling(receipt.getFileName()+".tmp");
        try {
            Files.writeString(pending,Json.writeCompact(Map.of("kind","snapshot-process-v1","pid",process.pid(),
                    "started",process.info().startInstant().map(Instant::toString).orElse(""))));
            try { Files.move(pending,receipt,StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException fallback) { Files.move(pending,receipt); }
        } finally { Files.deleteIfExists(pending); }
        return ()->{try {if(process.isAlive()) stop(process.toHandle());} finally {Files.deleteIfExists(receipt);}};
    }
    public static List<Map<String,Object>> recover(Path owner,boolean execute) throws Exception {
        List<Map<String,Object>> result=new ArrayList<>();Path directory=owner.resolve("processes");
        if(!Files.isDirectory(directory)) return result;
        try(var files=Files.list(directory)) {
            for(Path receipt:files.toList()) {
                if(!receipt.getFileName().toString().matches("[a-f0-9]{32}\\.json(?:\\.tmp)?") || !Files.isRegularFile(receipt,LinkOption.NOFOLLOW_LINKS)) continue;
                Object parsed;
                try { parsed=Json.parseTree(Files.readString(receipt)); }
                catch(RuntimeException invalid) { result.add(Map.of("kind","process_receipt","path",receipt.toString(),"owned",false));continue; }
                if(!(parsed instanceof Map<?,?> row) || !"snapshot-process-v1".equals(row.get("kind")) || !(row.get("pid") instanceof Number)) continue;
                long pid=((Number)row.get("pid")).longValue();var process=ProcessHandle.of(pid);
                boolean matching=process.isPresent() && process.get().info().startInstant().map(Instant::toString).orElse("unknown").equals(row.get("started"));
                result.add(Map.of("kind","build_process","pid",pid,"owned",matching));
                if(execute) { if(matching) stop(process.get());Files.deleteIfExists(receipt); }
            }
        }return result;
    }
    private static void stop(ProcessHandle process) throws Exception {
        var descendants=process.descendants().toList();descendants.forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();
        // Give killed processes a bounded opportunity to release files before workspace removal.
        if(process.isAlive()) process.onExit().get(5,java.util.concurrent.TimeUnit.SECONDS);
    }
}
