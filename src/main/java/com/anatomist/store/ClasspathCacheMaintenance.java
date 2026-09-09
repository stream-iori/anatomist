package com.anatomist.store;

import com.anatomist.config.StoragePaths;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** The regenerable classpath list cache only; never Maven artifacts or JDKs. */
public final class ClasspathCacheMaintenance {
    private ClasspathCacheMaintenance() {}
    public static Path directory() { return StoragePaths.home().resolve("cache/classpath"); }
    public static Map<String,Object> collect(int days,boolean execute) throws Exception {
        Path root=directory(); if(!Files.isDirectory(root)) return Map.of("bytes",0,"files",0);
        try(var lock=IndexLock.forWrite(root.resolve("maintenance"))) {
            long cutoff=Instant.now().minusSeconds((long)days*86400).toEpochMilli(),bytes=0;int count=0;
            try(var stream=Files.list(root)) {
                for(Path file:stream.toList()) {
                    String name=file.getFileName().toString();
                    if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || !(name.matches("[a-f0-9]{64}\\.txt") || name.startsWith("classpath-") && name.endsWith(".tmp"))) continue;
                    if(name.endsWith(".tmp") || Files.getLastModifiedTime(file).toMillis()<cutoff) {
                        bytes+=Files.size(file);count++;if(execute) Files.delete(file);
                    }
                }
            }
            return Map.of("bytes",bytes,"files",count);
        }
    }
}
