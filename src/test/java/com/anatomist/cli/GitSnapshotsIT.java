package com.anatomist.cli;

import com.anatomist.application.SnapshotService;
import com.anatomist.json.Json;
import com.anatomist.query.QueryService;
import com.anatomist.query.VersionDiffService;
import com.anatomist.version.*;
import com.anatomist.store.SnapshotCatalog;
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
        write("pom.xml","<project><modelVersion>4.0.0</modelVersion><groupId>p</groupId><artifactId>sample</artifactId><version>1</version></project>");
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

    @Test void incrementalCommitMatchesFullAndRetainsIncomingCalls() throws Exception {
        isolated(()->{
            write("src/main/java/p/B.java","package p; public class B { public int call() { return new A().value(); } }");
            write("src/main/java/p/C.java","package p; public class C { public int call() { return new B().call(); } }");
            commit("callers"); var a=build("HEAD");
            write("src/main/java/p/A.java","package p; public class A { public int value() { return 2; } }");
            commit("body change"); var incremental=build("HEAD");
            Map<?,?> metrics=(Map<?,?>)Json.parseTree(incremental.entry().metrics());
            assertEquals("incremental",metrics.get("mode"),metrics.toString());
            assertEquals(1,((Number)metrics.get("reparsed_files")).intValue());
            var full=service.build("HEAD",Json.writeCompact(options),true,IndexCommand.snapshotBuilder(options,project));
            assertEquals(canonical(full.database()),canonical(incremental.database()));
            var diff=new VersionDiffService().compare(service,a.entry(),incremental.entry(),"MAIN",null,true,3);
            assertTrue(diff.changes().stream().anyMatch(r->r.get("record").equals("impact") && r.get("entity").toString().endsWith("p.C#call()")),canonical(a.database()).toString());
            assertFalse(diff.changes().stream().anyMatch(r->r.get("record").equals("relation_change")),diff.json().toString());
        });
    }

    @Test void symbolRemovalReparsesCallersAndMatchesFull() throws Exception {
        isolated(()->{
            write("src/main/java/p/B.java","package p; public class B { public int call() { return new A().value(); } }");
            commit("caller"); var a=build("HEAD");
            write("src/main/java/p/A.java","package p; public class A { public int renamed() { return 2; } }");
            commit("rename"); var incremental=build("HEAD");
            var full=service.build("HEAD",Json.writeCompact(options),true,IndexCommand.snapshotBuilder(options,project));
            assertEquals(canonical(full.database()),canonical(incremental.database()));
            var diff=new VersionDiffService().compare(service,a.entry(),incremental.entry(),"MAIN",null,true,0);
            assertTrue(diff.changes().stream().anyMatch(r->r.get("record").equals("relation_change")));
            assertEquals(false,diff.evidence().get("negative_conclusion_safe"));
        });
    }

    @Test void emptyCommitIsRegisteredWithoutReparsing() throws Exception {
        isolated(()->{
            var a=build("HEAD");
            GitRepository.text(project,"commit","--allow-empty","-qm","empty");
            String sha=service.git().commit("HEAD"); var b=build("HEAD");
            assertEquals(sha,service.resolve("HEAD").commit());
            assertEquals(a.entry().sourceSnapshot(),b.entry().sourceSnapshot());
            assertEquals("noop",((Map<?,?>)Json.parseTree(b.entry().metrics())).get("mode"));
            try(QueryService query=new QueryService(b.database());Statement s=query.connection().createStatement();
                ResultSet r=s.executeQuery("SELECT value FROM project_meta WHERE key='source_git_commit'")) {
                assertTrue(r.next());assertEquals(sha,r.getString(1));
            }
        });
    }

    @Test void linkedWorktreeSharesCommitSnapshotsAndIsolatesDirtyHeads() throws Exception {
        isolated(()->{
            var committed=build("HEAD"); Path linked=temporary.resolve("linked");
            GitRepository.text(project,"worktree","add","--detach",linked.toString(),"HEAD");
            SnapshotService other=new SnapshotService(linked);
            assertEquals(service.directory(),other.directory());
            assertEquals(committed.entry().id(),other.resolve("HEAD").id());
            write("src/main/java/p/A.java","package p; public class A { public int value() { return 3; } }");
            var dirty=build("WORKTREE");
            assertThrows(SnapshotException.class,()->other.resolve("WORKTREE"));
            assertEquals(dirty.entry().id(),service.resolve("WORKTREE").id());
            Files.writeString(linked.resolve("src/main/java/p/A.java"),Files.readString(project.resolve("src/main/java/p/A.java")));
            var otherDirty=other.build("WORKTREE",Json.writeCompact(options),false,IndexCommand.snapshotBuilder(options,linked));
            assertNotEquals(dirty.entry().id(),otherDirty.entry().id());
            assertEquals(dirty.entry().id(),service.resolve("WORKTREE").id());
        });
    }

    @Test void identicalWorktreeAfterEmptyCommitGetsNewCommitIdentity() throws Exception {
        isolated(()->{
            var a=build("WORKTREE");GitRepository.text(project,"commit","--allow-empty","-qm","empty");
            var b=build("WORKTREE");
            assertNotEquals(a.entry().id(),b.entry().id());
            assertEquals(service.git().commit("HEAD"),b.entry().commit());
            assertEquals(a.entry().sourceSnapshot(),b.entry().sourceSnapshot());
        });
    }

    @Test void autoDiffBuildAndPipelineEmitValidStreams() throws Exception {
        isolated(()->{
            write("src/main/java/p/A.java","package p; public class A { public int value() { return 8; } }");
            String json=cli("diff","--project",project.toString(),"--base","HEAD","--target","WORKTREE","--no-classpath","--java-version","25","--format","json");
            assertEquals("anatomist-diff/v1",((Map<?,?>)Json.parseTree(json)).get("contract"));
            String source=cli("pipeline","--project",project.toString(),"--ref","HEAD","--", "resolve","p.A#value()","--kind","callable","--exact","--unique","--then","source");
            assertTrue(source.contains("return 1"),source);
            assertTrue(source.lines().reduce((a,b)->b).orElseThrow().contains("evidence"));
        });
    }

    private String cli(String... args) throws Exception {
        PrintStream previous=System.out; ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(PrintStream capture=new PrintStream(bytes)) {
            System.setOut(capture);
            assertEquals(0,AnatomistCli.commandLine(args).execute(args),bytes.toString());
        } finally { System.setOut(previous); }
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test void cacheReuseGcPreviewPinsAndActiveReaders() throws Exception {
        isolated(()->{
            var head=build("HEAD");
            write("src/main/java/p/A.java","package p; public class A { public int value() { return 2; } }");
            var old=build("WORKTREE");
            write("src/main/java/p/A.java","package p; public class A { public int value() { return 3; } }");
            var latest=build("WORKTREE");
            assertTrue(((Number)((Map<?,?>)Json.parseTree(latest.entry().metrics())).get("source_reused_files")).intValue()>0);
            try(var catalog=new SnapshotCatalog(service.directory())) { catalog.pin(old.entry().id(),true); }
            var pinned=com.anatomist.application.SnapshotMaintenance.collect(service,0,true);
            assertEquals(0,((Number)pinned.get("deleted")).intValue());
            try(var catalog=new SnapshotCatalog(service.directory())) { catalog.pin(old.entry().id(),false); }
            var preview=com.anatomist.application.SnapshotMaintenance.collect(service,0,false);
            assertEquals(1,((List<?>)preview.get("candidates")).size());assertTrue(Files.exists(old.database()));
            try(QueryService active=new QueryService(old.database())) {
                assertEquals(0,((Number)com.anatomist.application.SnapshotMaintenance.collect(service,0,true).get("deleted")).intValue());
            }
            var collected=com.anatomist.application.SnapshotMaintenance.collect(service,0,true);
            assertEquals(1,((Number)collected.get("deleted")).intValue());assertFalse(Files.exists(old.database()));
            try(QueryService query=new QueryService(head.database())) {
                assertTrue(new com.anatomist.query.IndexedSourceVerifier(query.connection(),head.database()).verify("src/main/java/p/A.java").current());
            }
            assertEquals(latest.entry().id(),service.resolve("WORKTREE").id());
        });
    }

    @Test void profilesRemainDistinctAndDiffReportsEnvironment() throws Exception {
        isolated(()->{
            var a=build("HEAD");List<String> other=List.of("--no-classpath","--java-version","17");
            var b=service.build("HEAD",Json.writeCompact(other),false,IndexCommand.snapshotBuilder(other,project));
            assertEquals("SNAPSHOT_AMBIGUOUS",assertThrows(SnapshotException.class,()->service.resolve("HEAD")).code());
            var diff=new VersionDiffService().compare(service,a.entry(),b.entry(),"MAIN",null,false,3);
            assertEquals(true,diff.header().get("environment_changed"));
            assertEquals(false,diff.evidence().get("negative_conclusion_safe"));
            assertEquals(a.entry().id(),service.resolve("snapshot:"+a.entry().id()).id());
        });
    }

    @Test void fieldInitializerAndTypeChangesAreCompared() throws Exception {
        isolated(()->{
            write("src/main/java/p/A.java","package p; public class A { public int count=1; }");commit("field");
            var a=build("HEAD");
            write("src/main/java/p/A.java","package p; public class A { public int count=2; }");
            var b=build("WORKTREE");
            var body=new VersionDiffService().compare(service,a.entry(),b.entry(),"MAIN",null,false,3);
            Map<String,Object> change=body.changes().stream().filter(r->r.get("record").equals("declaration_change")
                    && r.get("entity").toString().endsWith("p.A#count")).findFirst().orElseThrow();
            assertEquals(false,change.get("signature_changed"));assertEquals(true,change.get("content_changed"));
            write("src/main/java/p/A.java","package p; public class A { private long count=2; }");
            var c=build("WORKTREE");
            var type=new VersionDiffService().compare(service,b.entry(),c.entry(),"MAIN",null,false,3);
            assertTrue(type.changes().stream().anyMatch(r->r.get("record").equals("declaration_change")
                    && r.get("entity").toString().endsWith("p.A#count") && Boolean.TRUE.equals(r.get("signature_changed"))));
        });
    }

    @Test void renamedDeletedAndAddedFilesMatchFullIndex() throws Exception {
        isolated(()->{
            write("src/main/java/p/Removed.java","package p; class Removed {}");commit("before moves");
            var a=build("HEAD");
            Files.move(project.resolve("src/main/java/p/A.java"),project.resolve("src/main/java/p/Moved.java"));
            Files.delete(project.resolve("src/main/java/p/Removed.java"));
            write("src/main/java/p/Added.java","package p; class Added {}");commit("file changes");
            var b=build("HEAD");
            var full=service.build("HEAD",Json.writeCompact(options),true,IndexCommand.snapshotBuilder(options,project));
            assertEquals(canonical(full.database()),canonical(b.database()));
            var diff=new VersionDiffService().compare(service,a.entry(),b.entry(),"MAIN",null,false,3);
            for(String change:List.of("renamed","deleted","added"))
                assertTrue(diff.changes().stream().anyMatch(r->r.get("record").equals("file_change")
                        && change.equals(r.get("change"))),diff.json().toString());
        });
    }

    @Test void worktreeUsesDiskNotStageAndCapturesUntrackedAndIgnoredFiles() throws Exception {
        isolated(()->{
            write(".gitignore","ignored.txt\n");commit("ignore");
            var head=build("HEAD");
            write("src/main/java/p/A.java","package p; public class A { public int value() { return 2; } }");
            GitRepository.text(project,"add","src/main/java/p/A.java");
            byte[] staged=GitRepository.bytes(project,"diff","--cached","--binary");
            write("src/main/java/p/A.java","package p; public class A { public int value() { return 3; } }");
            write("src/main/java/p/New.java","package p; class New {}");write("ignored.txt","frozen ignored content");
            var disk=build("WORKTREE");
            assertArrayEquals(staged,GitRepository.bytes(project,"diff","--cached","--binary"));
            try(QueryService query=new QueryService(disk.database())) {
                assertTrue(Files.readString(com.anatomist.query.SnapshotSource.path(query.connection(),"src/main/java/p/A.java")).contains("return 3"));
            }
            var diff=new VersionDiffService().compare(service,head.entry(),disk.entry(),"MAIN",null,false,3);
            for(String path:List.of("ignored.txt","src/main/java/p/New.java"))
                assertTrue(diff.changes().stream().anyMatch(r->path.equals(r.get("path")) && "added".equals(r.get("change"))));
        });
    }

    @Test void mergeBaseResolvesDivergedHistory() throws Exception {
        isolated(()->{
            String ancestor=service.git().commit("HEAD");
            write("left.txt","left");commit("left");String left=service.git().commit("HEAD");
            // A detached checkout here belongs only to the temporary test fixture.
            GitRepository.text(project,"checkout","--detach",ancestor);
            write("right.txt","right");commit("right");String right=service.git().commit("HEAD");
            Map<?,?> output=(Map<?,?>)Json.parseTree(cli("diff","--project",project.toString(),"--base",left,
                    "--target",right,"--merge-base","--no-classpath","--java-version","25","--format","json"));
            Map<?,?> comparison=(Map<?,?>)output.get("comparison");
            assertEquals(ancestor,((Map<?,?>)comparison.get("base")).get("commit"));
            assertEquals(right,service.git().commit("HEAD"));
        });
    }

    @Test void externalSourceRootsCannotPublishUnfrozenEvidence() throws Exception {
        isolated(()->{
            Path external=Files.createDirectories(temporary.resolve("external"));
            Files.writeString(external.resolve("Outside.java"),"public class Outside {}");
            List<String> externalOptions=new ArrayList<>(options);
            externalOptions.addAll(List.of("--project-source",external.toString()));
            SnapshotException failure=assertThrows(SnapshotException.class,()->service.build("HEAD",
                    Json.writeCompact(externalOptions),false,IndexCommand.snapshotBuilder(externalOptions,project)));
            assertEquals("SNAPSHOT_SOURCE_OUTSIDE_PROJECT",failure.code(),failure.getMessage());
            try(var catalog=new SnapshotCatalog(service.directory())) {
                assertTrue(catalog.list().stream().noneMatch(e->e.status().equals("READY")));
            }
        });
    }

    @Test void concurrentIdenticalBuildPublishesOnce() throws Exception {
        isolated(()->{
            var count=new java.util.concurrent.atomic.AtomicInteger();
            SnapshotService.Builder actual=IndexCommand.snapshotBuilder(options,project);
            SnapshotService.Builder counted=(p,db,inc)->{ count.incrementAndGet();return actual.build(p,db,inc); };
            try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var a=executor.submit(()->service.build("HEAD",Json.writeCompact(options),false,counted));
                var b=executor.submit(()->service.build("HEAD",Json.writeCompact(options),false,counted));
                assertEquals(a.get().entry().id(),b.get().entry().id());assertEquals(1,count.get());
            }
        });
    }

    private List<String> canonical(Path db) throws Exception {
        List<String> result=new ArrayList<>();
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db)) {
            for(String table:List.of("nodes","edges","annotations","annotation_meta_relations","analysis_coverage","file_dependencies","symbol_dependencies")) {
                List<String> columns=new ArrayList<>();
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA table_info("+table+")")) {
                    while(r.next()) if(!r.getString("name").equals("id") || table.equals("nodes")) columns.add("quote(\""+r.getString("name")+"\")");
                }
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT "+String.join("||'|'||",columns)+" FROM "+table)) {
                    while(r.next()) result.add(table+":"+r.getString(1));
                }
            }
            for(String sql:List.of("SELECT o.caller_id,cs.begin_line,cs.begin_column,cs.end_line,cs.end_column,cs.ordinal,cs.context,cs.syntax_target,t.target_id,t.external_target_fqn,t.resolution_status FROM call_sites cs JOIN call_site_owners o ON o.owner_pk=cs.owner_pk JOIN call_site_targets t ON t.call_site_pk=cs.site_pk",
                    "SELECT provider_id,source_file,hash,contract_hash FROM file_cache")) {
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)) {
                    while(r.next()) {List<String> row=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++) row.add(r.getString(i));result.add(row.toString());}
                }
            }
        }
        Collections.sort(result);return result;
    }
}
