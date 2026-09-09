package com.anatomist.query;

import com.anatomist.version.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommittedTextChangesTest {
    @TempDir Path root;
    @Test void batchMatchesFrozenDiffAndRejectsDifferentBytes() throws Exception {
        Path repo=Files.createDirectories(root.resolve("repo"));
        GitRepository.text(repo,"init","-q");GitRepository.text(repo,"config","user.name","Diff Test");GitRepository.text(repo,"config","user.email","test@example.invalid");
        for(String path:List.of("A.java","nested/B file.java")) {Path file=repo.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,"class A {\n int value(){return 1;}\n}\n");}
        GitRepository.text(repo,"add",".");GitRepository.text(repo,"commit","-qm","before");
        String before=GitRepository.text(repo,"rev-parse","HEAD");var old=SnapshotCapture.inventory(repo,List.of());
        Path oldSource=root.resolve("before");Files.copy(repo.resolve("A.java"),oldSource);
        for(String path:old.keySet()) Files.writeString(repo.resolve(path),"class A {\n int value(){return 2;}\n}\n");
        GitRepository.text(repo,"add",".");GitRepository.text(repo,"commit","-qm","after");
        String after=GitRepository.text(repo,"rev-parse","HEAD");var current=SnapshotCapture.inventory(repo,List.of());
        var batch=CommittedTextChanges.compare(GitRepository.open(repo),before,after,old.keySet(),old,current);
        assertEquals(old.keySet(),batch.keySet());
        var expected=DiffTextChanges.compare(oldSource,repo.resolve("A.java"));batch.values().forEach(hunks->assertEquals(expected,hunks));
        var wrong=new HashMap<>(current);wrong.put("A.java","0".repeat(64));
        assertFalse(CommittedTextChanges.compare(GitRepository.open(repo),before,after,old.keySet(),old,wrong).containsKey("A.java"));
    }
}
