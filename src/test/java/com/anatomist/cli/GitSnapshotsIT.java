package com.anatomist.cli;

import com.anatomist.application.SnapshotService;
import com.anatomist.json.Json;
import com.anatomist.query.QueryService;
import com.anatomist.query.VersionDiffService;
import com.anatomist.version.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("system-properties")
class GitSnapshotsIT {
    @TempDir Path temporary;
    private final List<String> options=List.of("--no-classpath","--java-version","25");
    private Path project;
    private SnapshotService service;

    private void setup() throws Exception {
        project=Files.createDirectories(temporary.resolve("project")).toRealPath();
        write("src/main/java/p/A.java","package p; public class A { public int value() { return 1; } }");
        GitRepository.text(project,"init","-q");
        GitRepository.text(project,"config","user.name","Snapshot Test");
        GitRepository.text(project,"config","user.email","snapshot@example.test");
        commit("initial"); service=new SnapshotService(project);
    }
    private void write(String path,String text) throws Exception {
        Path file=project.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file,text);
    }
    private void commit(String message) {
        GitRepository.text(project,"add","."); GitRepository.text(project,"commit","-qm",message);
    }
    private SnapshotService.Built build(String ref) {
        return service.build(ref,Json.writeCompact(options),false,IndexCommand.snapshotBuilder(options,project));
    }
    private void isolated(Checked work) throws Exception {
        String prior=System.getProperty("user.home");
        System.setProperty("user.home",temporary.resolve("home").toString());
        try { setup(); work.run(); } finally { System.setProperty("user.home",prior); }
    }
    @FunctionalInterface interface Checked { void run() throws Exception; }

    @Test void capturesHistoryAndWorktreeWithoutTouchingHeadOrStage() throws Exception {
        isolated(()->{
            String head=service.git().commit("HEAD");
            var a=build("HEAD"); assertFalse(a.reused());
            assertTrue(build("HEAD").reused());
            write("src/main/java/p/A.java","package p; public class A { public int value() { return 2; } }");
            byte[] stage=GitRepository.bytes(project,"diff","--cached","--binary");
            var b=build("WORKTREE");
            assertEquals(head,service.git().commit("HEAD"));
            assertArrayEquals(stage,GitRepository.bytes(project,"diff","--cached","--binary"));
            var diff=new VersionDiffService().compare(service,a.entry(),b.entry(),"MAIN",null,false,3);
            assertTrue(diff.changes().stream().anyMatch(r->r.get("record").equals("declaration_change")
                    && r.get("entity").toString().endsWith("p.A#value()")));
            try(QueryService query=new QueryService(a.database())) {
                Path frozen=com.anatomist.query.SnapshotSource.path(query.connection(),"src/main/java/p/A.java");
                assertTrue(Files.readString(frozen).contains("return 1"));
                assertTrue(new com.anatomist.query.IndexedSourceVerifier(query.connection(),a.database())
                        .verify("src/main/java/p/A.java").current());
            }
            assertFalse(Files.exists(service.directory().resolve("workspace")));
        });
    }

    @Test void ignoresLayoutAndCommentsInSemanticDiff() throws Exception {
        isolated(()->{
            var a=build("HEAD");
            write("src/main/java/p/A.java","package p;\n// comment\npublic class A {\n public int value() {\n return 1;\n }\n}\n");
            var b=build("WORKTREE");
            var diff=new VersionDiffService().compare(service,a.entry(),b.entry(),"MAIN",null,false,3);
            assertTrue(diff.changes().stream().anyMatch(r->r.get("record").equals("file_change")));
            assertFalse(diff.changes().stream().anyMatch(r->r.get("record").equals("declaration_change")),diff.json().toString());
        });
    }

    @Test void explicitSelectionsAndImmutableWrites() throws Exception {
        isolated(()->{
            var a=build("HEAD");
            VersionSelection selection=new VersionSelection(); selection.project=project; selection.ref="HEAD";
            assertEquals(a.database(),selection.resolve(null));
            assertThrows(IllegalArgumentException.class,()->selection.resolve(a.database()));
            assertThrows(SnapshotException.class,()->SnapshotFiles.requireMutable(a.database()));
            assertThrows(SnapshotException.class,()->service.resolve("snapshot:"+"f".repeat(32)));
            assertThrows(SnapshotException.class,()->SnapshotFiles.resolve(service.directory(),"../escape"));
        });
    }

    @Test void failedBuildIsNeverPublished() throws Exception {
        isolated(()->{
            var good=build("HEAD");
            write("src/main/java/p/A.java","package p; public class A { broken");
            assertThrows(SnapshotException.class,()->service.build("WORKTREE","broken",false,(root,db,incremental)->{
                Files.writeString(db,"partial"); throw new IOException("injected failure");
            }));
            assertEquals(good.entry().id(),service.resolve("HEAD").id());
            try(var catalog=new SnapshotCatalog(service.directory())) {
                assertTrue(catalog.list().stream().anyMatch(e->e.status().equals("FAILED")));
            }
            assertFalse(Files.exists(service.directory().resolve("workspace")));
        });
    }
}
