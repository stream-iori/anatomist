package com.anatomist.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DiffNavigationTest {
    private static DiffNavigation.Declaration node(String id,String kind,int start,int end) {
        return new DiffNavigation.Declaration(id,id,kind,".","MAIN","A.java",start,1,end,80,false);
    }
    private static Map<String,String> locate(List<DiffNavigation.Declaration> nodes,int start,int count) {
        return DiffNavigation.locate(nodes,"A.java",List.of(new DiffTextChanges.Lines(start,count)));
    }
    @Test void memberEditsDoNotRepeatOuterTypes() {
        var nodes=List.of(node("A","CLASS",1,100),node("nested","CLASS",10,90),node("method","METHOD",20,30));
        assertEquals(Map.of("method","declaration"),locate(nodes,24,2));
    }
    @Test void ownTypeEditAndMemberEditAreBothKept() {
        var nodes=List.of(node("A","RECORD",1,100),node("constructor","CONSTRUCTOR",20,30));
        var hits=DiffNavigation.locate(nodes,"A.java",List.of(new DiffTextChanges.Lines(1,1),new DiffTextChanges.Lines(22,1)));
        assertEquals(Set.of("A","constructor"),hits.keySet());
    }
    @Test void spanningHunkKeepsEveryMemberWithoutOuterDuplication() {
        assertEquals(Set.of("a","b"),locate(List.of(node("A","CLASS",1,90),node("a","METHOD",10,20),
                node("b","CONSTRUCTOR",21,30)),10,21).keySet());
    }
    @Test void sameLineDeclarationsAreCandidates() {
        assertEquals(Map.of("a","candidate","b","candidate"),locate(List.of(node("a","FIELD",5,5),node("b","FIELD",5,5)),5,1));
    }
    @Test void zeroLengthSideDoesNotTouchAdjacentMethod() {
        assertTrue(locate(List.of(node("a","METHOD",2,5)),5,0).isEmpty());
    }
    @Test void fileLevelCommentsHaveNoInventedDeclaration() {
        assertTrue(locate(List.of(node("A","CLASS",5,30)),1,2).isEmpty());
    }
    @Test void unavailableFieldRangeFallsBackToOwner() {
        assertEquals(Map.of("A","owner"),locate(List.of(node("A","CLASS",1,30),node("field","FIELD",0,0)),5,1));
        assertFalse(node("field","FIELD",0,0).anchor("snapshot","unlocated").containsKey("start_line"));
    }
    @Test void syntheticSourceIsNotInvented() {
        var generated=new DiffNavigation.Declaration("g","g","METHOD",".","MAIN","A.java",5,1,6,2,true);
        assertTrue(locate(List.of(generated),5,1).isEmpty());
        assertFalse(generated.anchor("snapshot","unlocated").containsKey("start_line"));
        assertEquals("derived",generated.anchor("snapshot","unlocated").get("origin"));
    }
    @Test void repeatedChangesAreDeduplicated() {
        var node=node("a","METHOD",2,20);
        assertEquals(Map.of("a","declaration"),DiffNavigation.locate(List.of(node),"A.java",
                List.of(new DiffTextChanges.Lines(3,1),new DiffTextChanges.Lines(8,1))));
    }
    @Test void gitTextComparisonIncludesCommentsAndFormatting(@TempDir Path dir) throws Exception {
        Path a=dir.resolve("before"),b=dir.resolve("after");
        Files.writeString(a,"a\nb\nc\n");Files.writeString(b,"a\n// comment\nb \nc\n");
        var hunks=DiffTextChanges.compare(a,b);
        assertFalse(hunks.isEmpty());
        assertTrue(hunks.stream().anyMatch(h->h.after().contains(2)));
        assertTrue(hunks.stream().anyMatch(h->h.before().contains(2)));
    }
    @Test void gitExitZeroMeansNoDifferences(@TempDir Path dir) throws Exception {
        Path a=dir.resolve("a"),b=dir.resolve("b");Files.writeString(a,"same\n");Files.writeString(b,"same\n");
        assertTrue(DiffTextChanges.compare(a,b).isEmpty());
    }
    @Test void missingTextIsAnErrorNotEmptyEvidence(@TempDir Path dir) {
        assertThrows(com.anatomist.version.SnapshotException.class,()->DiffTextChanges.compare(dir.resolve("a"),dir.resolve("b")));
    }
}
