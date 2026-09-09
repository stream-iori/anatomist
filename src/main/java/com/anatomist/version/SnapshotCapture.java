package com.anatomist.version;

import com.anatomist.config.ConfigLoader;
import com.anatomist.core.PathGlob;
import com.anatomist.store.FileCacheService;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Git visibility plus declared analysis inputs; never a recursive disk backup. */
public final class SnapshotCapture {
    public static final String POLICY = "git-inputs-v2";
    private SnapshotCapture() {}

    public static boolean runtimeFile(String path) {
        String p="/"+path.replace('\\','/');
        return p.contains("/.git/") || p.endsWith("/.git")
                || p.contains("/.anatomist/") && !p.endsWith("/.anatomist/config.toml");
    }

    public static Map<String,String> inventory(Path root, Collection<Path> inputs) throws IOException {
        return inventory(root,inputs,List.of());
    }
    public static Map<String,String> inventory(Path root, Collection<Path> inputs,Collection<Path> artifacts) throws IOException {
        root=root.toAbsolutePath().normalize();
        Set<Path> required=new HashSet<>();inputs.forEach(p->required.add(p.toAbsolutePath().normalize()));
        List<Path> buildOutputs=artifacts.stream().map(p->p.toAbsolutePath().normalize()).toList();
        Set<String> paths=new TreeSet<>();
        String listed=new String(GitRepository.bytes(root,"ls-files","--cached","--others","--exclude-standard","-z","--","."),java.nio.charset.StandardCharsets.UTF_8);
        for(String path:listed.split("\0")) if(!path.isEmpty()) paths.add(path);
        // Staging a deletion must not hide disk bytes still tracked by HEAD.
        String deleted=new String(GitRepository.bytes(root,"diff","--no-relative","--cached","--name-only","--diff-filter=D","-z","--","."),java.nio.charset.StandardCharsets.UTF_8);
        if(!deleted.isEmpty()) {
            Path repository=Path.of(GitRepository.text(root,"rev-parse","--show-toplevel")).toRealPath();
            Path canonicalRoot=root.toRealPath();
            for(String path:deleted.split("\0")) if(!path.isEmpty()) {
                Path actual=repository.resolve(path).normalize();
                if(actual.startsWith(canonicalRoot)) paths.add(SnapshotFiles.relative(canonicalRoot,actual));
            }
        }
        var config=ConfigLoader.load(root);
        if(!config.captureIncludeIgnored().isEmpty()) {
            var globs=config.captureIncludeIgnored().stream().map(PathGlob::new).toList();
            String ignored=new String(GitRepository.bytes(root,"ls-files","--others","--ignored","--exclude-standard","-z","--","."),java.nio.charset.StandardCharsets.UTF_8);
            for(String path:ignored.split("\0")) if(!path.isEmpty() && globs.stream().anyMatch(g->g.matches(path))) paths.add(path);
        }
        paths.add(".anatomist/config.toml");
        for(Path input:inputs) {
            Path actual=input.toAbsolutePath().normalize();
            if(!actual.startsWith(root)) throw new SnapshotException("SNAPSHOT_SOURCE_OUTSIDE_PROJECT","Input outside captured project: "+input);
            paths.add(SnapshotFiles.relative(root,actual));
        }
        Map<String,String> result=new TreeMap<>();
        Set<Path> checkedPaths=new HashSet<>();
        for(String path:paths) {
            if(runtimeFile(path)) continue;
            Path file=SnapshotFiles.resolve(root,path);
            if(!required.contains(file) && buildOutputs.stream().anyMatch(file::startsWith)) continue;
            for(Path component=file;!component.equals(root) && checkedPaths.add(component);component=component.getParent())
                if(Files.isSymbolicLink(component)) throw new SnapshotException("SNAPSHOT_SYMLINK_UNSUPPORTED","Snapshot input contains symbolic link: "+path);
            if(Files.isRegularFile(file)) result.put(path,FileCacheService.sha256(file));
        }
        return result;
    }
}
