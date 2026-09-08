package com.anatomist.version;

import com.anatomist.store.FileCacheService;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Bounded ownership and content checks for managed snapshot files. */
public final class SnapshotFiles {
    private SnapshotFiles() {}
    public static Map<String,String> inventory(Path root) throws IOException {
        Map<String,String> files = new TreeMap<>();
        Files.walkFileTree(root,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name=dir.getFileName().toString();
                return !dir.equals(root) && Set.of(".git", ".idea", "node_modules").contains(name)
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if(file.getFileName().toString().equals(".git")) return FileVisitResult.CONTINUE;
                if(Files.isSymbolicLink(file)) throw new SnapshotException("SNAPSHOT_SYMLINK_UNSUPPORTED",
                        "Snapshot input contains a symbolic link: " + root.relativize(file));
                if(attrs.isRegularFile()) files.put(relative(root,file), FileCacheService.sha256(file));
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }
    public static String fingerprint(Map<String,String> files) {
        StringBuilder out=new StringBuilder();
        new TreeMap<>(files).forEach((path,hash)->out.append(path).append('\0').append(hash).append('\n'));
        return FileCacheService.sha256OfString(out.toString());
    }
    public static void copy(Path from,Path to,Map<String,String> files) throws IOException {
        for(var entry:files.entrySet()) {
            Path target=resolve(to,entry.getKey()); Files.createDirectories(target.getParent());
            Files.copy(resolve(from,entry.getKey()),target,StandardCopyOption.REPLACE_EXISTING);
            if(!entry.getValue().equals(FileCacheService.sha256(target)))
                throw new SnapshotException("WORKTREE_CHANGED", "Input changed during capture: " + entry.getKey());
        }
    }
    public static Path resolve(Path root,String relative) {
        Path value=root.resolve(relative).normalize();
        if(Path.of(relative).isAbsolute() || !value.startsWith(root.normalize()))
            throw new SnapshotException("SNAPSHOT_PATH_INVALID","Path escapes snapshot: " + relative);
        return value;
    }
    public static String relative(Path root,Path file) { return root.relativize(file).toString().replace('\\','/'); }
    public static void deleteOwned(Path owner,Path path) throws IOException {
        Path base=owner.toAbsolutePath().normalize(), target=path.toAbsolutePath().normalize();
        if(target.equals(base) || !target.startsWith(base))
            throw new SnapshotException("SNAPSHOT_PATH_INVALID","Refusing to delete outside managed child: " + target);
        if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(target,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException failure) throws IOException {
                if(failure!=null) throw failure; Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
    public static void requireMutable(Path database) {
        Path actual=database.toAbsolutePath().normalize();
        try { if(Files.exists(actual)) actual=actual.toRealPath(); } catch(IOException failure) {
            throw new SnapshotException("SNAPSHOT_PATH_INVALID","Cannot resolve database",failure);
        }
        if(Files.exists(actual.resolveSibling(actual.getFileName()+".snapshot")))
            throw new SnapshotException("SNAPSHOT_IMMUTABLE","Published snapshot is immutable: " + database);
    }
}
