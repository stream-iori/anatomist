package com.anatomist.query;

import com.anatomist.store.IndexLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DiffRowsTest {
    @TempDir Path root;
    @Test void spillPreservesRowsFiltersAndReleasesOwnedFiles() throws Exception {
        List<Map<String,Object>> expected=List.of(Map.of("record","a","value","你好"),Map.of("record","b","value",42));
        try(var rows=new DiffRows(root,8)) {
            expected.forEach(rows::add);assertEquals(com.anatomist.json.Json.writeCompact(expected),com.anatomist.json.Json.writeCompact(rows));assertTrue(rows.spilledBytes()>0);
            Path data;try(var files=Files.list(root)) { data=files.filter(p->p.toString().endsWith(".jsonl")).findFirst().orElseThrow(); }
            try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
                assertTrue(executor.submit(()->{
                    try(var lock=IndexLock.forWrite(data,0)) {return false;}
                    catch(IndexLock.LockTimeoutException expectedTimeout) {return true;}
                }).get());
            }
            try(var filtered=rows.filtered(r->r.get("record").equals("b"))) { assertEquals(com.anatomist.json.Json.writeCompact(List.of(expected.get(1))),com.anatomist.json.Json.writeCompact(filtered)); }
        }
        try(var files=Files.list(root)) {assertTrue(files.allMatch(p->p.toString().endsWith(".lock")));}
    }
}
