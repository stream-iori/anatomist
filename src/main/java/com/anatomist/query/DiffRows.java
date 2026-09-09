package com.anatomist.query;

import com.anatomist.json.Json;
import com.anatomist.store.IndexLock;
import com.anatomist.version.SnapshotException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Append-only result rows; spills after a bounded serialized byte count. */
final class DiffRows extends AbstractList<Map<String,Object>> implements AutoCloseable {
    private final Path directory;
    private final long threshold;
    private long bytes;
    private List<Map<String,Object>> memory=new ArrayList<>();
    private final List<Long> offsets=new ArrayList<>();
    private RandomAccessFile file;
    private Path path,owner;
    private IndexLock lease;
    DiffRows(Path directory) { this(directory,8L*1024*1024); }
    DiffRows(Path directory,long threshold) { this.directory=directory;this.threshold=threshold; }
    Path directory() { return directory; }
    long spilledBytes() { return file==null?0:bytes; }
    @Override public int size() { return file==null?memory.size():offsets.size(); }
    @Override public boolean add(Map<String,Object> row) {
        byte[] encoded=Json.writeCompact(row).getBytes(StandardCharsets.UTF_8);
        try {
            if(file==null && bytes+encoded.length>threshold) {
                Files.createDirectories(directory);path=directory.resolve(UUID.randomUUID().toString().replace("-","")+".jsonl");
                owner=path.resolveSibling(path.getFileName()+".owner");lease=IndexLock.forRead(path);
                Files.writeString(owner,"anatomist-diff-results-v1");file=new RandomAccessFile(path.toFile(),"rw");
                for(var previous:memory) append(Json.writeCompact(previous).getBytes(StandardCharsets.UTF_8));
                memory=null;
            }
            if(file==null) memory.add(row); else append(encoded);
            bytes+=encoded.length;return true;
        } catch(IOException failure) { close();throw new SnapshotException("DIFF_RESULT_STORAGE_FAILED",failure.getMessage(),failure); }
    }
    private void append(byte[] row) throws IOException {
        long offset=file.length();file.seek(offset);offsets.add(offset);file.writeInt(row.length);file.write(row);
    }
    @SuppressWarnings("unchecked") @Override public Map<String,Object> get(int index) {
        Objects.checkIndex(index,size());if(file==null) return memory.get(index);
        try { file.seek(offsets.get(index));byte[] row=new byte[file.readInt()];file.readFully(row);return (Map<String,Object>)Json.parseTree(new String(row,StandardCharsets.UTF_8)); }
        catch(IOException failure) { throw new SnapshotException("DIFF_RESULT_STORAGE_FAILED",failure.getMessage(),failure); }
    }
    DiffRows filtered(java.util.function.Predicate<Map<String,Object>> predicate) {
        DiffRows result=new DiffRows(directory,threshold);
        try { for(var row:this) if(predicate.test(row)) result.add(row);return result; }
        catch(RuntimeException failure) { result.close();throw failure; }
    }
    @Override public void close() {
        try {
            if(file!=null) file.close();
            if(path!=null) Files.deleteIfExists(path);
            if(owner!=null) Files.deleteIfExists(owner);
        } catch(IOException failure) {
            System.err.println(Json.writeCompact(Map.of("record","warning","code","DIFF_CLEANUP_PENDING","message",failure.getMessage())));
        } finally { if(lease!=null) {lease.close();lease=null;} }
    }
}
