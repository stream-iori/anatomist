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

    private static Map<?,?> capability(VersionDiffService.Result result,String name) {
        return (Map<?,?>)((Map<?,?>)result.evidence().get("capabilities")).get(name);
    }

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

    @Test void navigatesLayoutAndCommentsAsTextChanges() throws Exception {
        isolated(()->{
            var a=build("HEAD");
            write("src/main/java/p/A.java","package p;\n// comment\npublic class A {\n public int value() {\n return 1;\n }\n}\n");
            var b=build("WORKTREE");
            var diff=new VersionDiffService().compare(service,a.entry(),b.entry(),"MAIN",null,false,3);
            assertTrue(diff.changes().stream().anyMatch(r->r.get("record").equals("file_change")));
            assertTrue(diff.changes().stream().anyMatch(r->r.get("record").equals("declaration_change")),diff.json().toString());
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
            assertEquals(false,capability(diff,"impact").get("negative_conclusion_safe"));
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
            assertEquals("anatomist-diff/v2",((Map<?,?>)Json.parseTree(json)).get("contract"));
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
            assertEquals(false,capability(diff,"relations").get("negative_conclusion_safe"));
            assertEquals(a.entry().id(),service.resolve("snapshot:"+a.entry().id()).id());
        });
    }

    @Test void fieldChangesNavigateToOwnerWhenFieldRangeIsUnavailable() throws Exception {
        isolated(()->{
            write("src/main/java/p/A.java","package p; public class A { public int count=1; }");commit("field");
            var a=build("HEAD");
            write("src/main/java/p/A.java","package p; public class A { public int count=2; }");
            var b=build("WORKTREE");
            var body=new VersionDiffService().compare(service,a.entry(),b.entry(),"MAIN",null,false,3);
            Map<String,Object> change=body.changes().stream().filter(r->r.get("record").equals("declaration_change")
                    && r.get("entity").toString().endsWith("p.A")).findFirst().orElseThrow();
            assertFalse(change.containsKey("signature_changed"));assertFalse(change.containsKey("content_changed"));
            assertEquals("owner",((Map<?,?>)change.get("after")).get("precision"));
            assertEquals(false,capability(body,"declarations").get("negative_conclusion_safe"));
            write("src/main/java/p/A.java","package p; public class A { private long count=2; }");
            var c=build("WORKTREE");
            var type=new VersionDiffService().compare(service,b.entry(),c.entry(),"MAIN",null,false,3);
            assertTrue(type.changes().stream().anyMatch(r->r.get("record").equals("declaration_change")
                    && r.get("entity").toString().endsWith("p.A")));
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
            var request=(Map<?,?>)comparison.get("request");
            assertEquals("merge_base",request.get("mode"));assertEquals(left,((Map<?,?>)request.get("base")).get("commit"));
            Map<?,?> tips=(Map<?,?>)Json.parseTree(cli("diff","--project",project.toString(),"--base",left,
                    "--target","HEAD","--no-classpath","--java-version","25","--format","json","--view","calls"));
            var tipHeader=(Map<?,?>)tips.get("comparison");
            assertEquals(left,((Map<?,?>)tipHeader.get("base")).get("commit"));
            assertEquals("endpoints",((Map<?,?>)tipHeader.get("request")).get("mode"));
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

    @Test void unindexedTestChangesKeepFileEvidenceAndRejectNegativeDeclarationConclusion() throws Exception {
        isolated(()->{
            write("src/test/java/p/Check.java","package p; class Check { int check() { return 1; } }");commit("test source");
            var a=build("HEAD");write("src/test/java/p/Check.java","package p; class Check { int check() { return 2; } }");var b=build("WORKTREE");
            var diff=new VersionDiffService().compare(service,a.entry(),b.entry(),"TEST",null,false,3);
            assertTrue(diff.changes().stream().anyMatch(r->"src/test/java/p/Check.java".equals(r.get("path"))));
            assertEquals(true,capability(diff,"files").get("complete"));
            assertEquals(false,capability(diff,"declarations").get("negative_conclusion_safe"));
            assertEquals("not_requested",capability(diff,"impact").get("status"));
            assertFalse(diff.evidence().containsKey("negative_conclusion_safe"));
        });
    }

    @Test void navigationAnchorsResolveBothFrozenMethodBodies() throws Exception {
        isolated(()->{
            var a=build("HEAD");write("src/main/java/p/A.java","package p; public class A { public int value() { return 2; } }");var b=build("WORKTREE");
            var diff=new VersionDiffService().compare(service,a.entry(),b.entry(),"MAIN",null,false,3);
            var row=diff.changes().stream().filter(r->r.get("record").equals("declaration_change") && r.get("entity").toString().endsWith("#value()")).findFirst().orElseThrow();
            for(String side:List.of("before","after")) {
                var anchor=(Map<?,?>)row.get(side);
                String source=cli("pipeline","--project",project.toString(),"--snapshot",anchor.get("snapshot_id").toString(),"--",
                        "resolve",anchor.get("id").toString(),"--unique","--then","source");
                assertTrue(source.contains(side.equals("before")?"return 1":"return 2"),source);
            }
            assertFalse(((Map<?,?>)diff.header().get("base")).containsKey("metrics"));
        });
    }

    @Test void missingModulesAndUnrequestedImpactOptionsFailExplicitly() throws Exception {
        isolated(()->{
            var a=build("HEAD");
            assertThrows(IllegalArgumentException.class,()->new VersionDiffService().compare(service,a.entry(),a.entry(),"MAIN","missing",false,3));
            assertEquals(2,new picocli.CommandLine(new DiffCommand()).execute("--base","HEAD","--target","WORKTREE","--impact-scope","TEST"));
            assertEquals(2,new picocli.CommandLine(new DiffCommand()).execute("--base","HEAD","--target","WORKTREE","--impact-dispatch","auto"));
            assertEquals(2,new picocli.CommandLine(new DiffCommand()).execute("--base","HEAD","--target","WORKTREE","--view","invalid"));
        });
    }

    @Test void multipleOriginsAndTestCallersAreReturnedFromRealIndex() throws Exception {
        isolated(()->{
            write(".anatomist/config.toml","[scan]\nscopes = [\"MAIN\", \"TEST\"]\n");
            write("src/main/java/p/A.java","package p;\npublic class A {\n public int a() { return 1; }\n public int b() { return 2; }\n}\n");
            write("src/test/java/p/Check.java","package p; public class Check { public int check() { A a=new A(); return a.a()+a.b(); } }");
            commit("two origins");var a=build("HEAD");
            write("src/main/java/p/A.java","package p;\npublic class A {\n public int a() { return 3; }\n public int b() { return 4; }\n}\n");var b=build("WORKTREE");
            var diff=new VersionDiffService().compare(service,a.entry(),b.entry(),"MAIN",null,true,3,"TEST",null);
            var impacts=diff.changes().stream().filter(r->r.get("record").equals("impact")).toList();
            assertEquals(4,impacts.size(),diff.json().toString());
            assertTrue(impacts.stream().allMatch(r->r.get("entity").toString().endsWith("p.Check#check()")));
            assertFalse(diff.changes().stream().anyMatch(r->r.get("record").equals("declaration_change") && r.get("entity").toString().endsWith("::p.A")));
        });
    }

    @Test void deletedMethodKeepsOnlyOldAnchorAndNewMethodOnlyNewAnchor() throws Exception {
        isolated(()->{
            var a=build("HEAD");write("src/main/java/p/A.java","package p; public class A { public int renamed() { return 1; } }");var b=build("WORKTREE");
            var diff=new VersionDiffService().compare(service,a.entry(),b.entry(),"MAIN",null,false,3);
            var rows=diff.changes().stream().filter(r->r.get("record").equals("declaration_change")).toList();
            var deleted=rows.stream().filter(r->"deleted".equals(r.get("change"))).findFirst().orElseThrow();
            var added=rows.stream().filter(r->"added".equals(r.get("change"))).findFirst().orElseThrow();
            assertTrue(deleted.containsKey("before"));assertFalse(deleted.containsKey("after"));
            assertFalse(added.containsKey("before"));assertTrue(added.containsKey("after"));
        });
    }

    @Test void fileLevelCommentDoesNotInventMethodChanges() throws Exception {
        isolated(()->{
            var a=build("HEAD");write("src/main/java/p/A.java","// file comment\npackage p; public class A { public int value() { return 1; } }");var b=build("WORKTREE");
            var diff=new VersionDiffService().compare(service,a.entry(),b.entry(),"MAIN",null,false,3);
            assertFalse(diff.changes().stream().anyMatch(r->r.get("record").equals("declaration_change")),diff.json().toString());
            assertTrue(diff.changes().getFirst().containsKey("before"));assertTrue(diff.changes().getFirst().containsKey("after"));
        });
    }

    @Test void modulePresentOnOnlyOneSideIsAValidAddition() throws Exception {
        isolated(()->{
            write(".anatomist/config.toml","[scan]\nsource_roots = [\"api@MAIN=src/main/java\"]\n");commit("api root");var a=build("HEAD");
            write("extra/src/main/java/q/Added.java","package q; public class Added { public int value() { return 1; } }");
            write(".anatomist/config.toml","[scan]\nsource_roots = [\"api@MAIN=src/main/java\", \"extra@MAIN=extra/src/main/java\"]\n");var b=build("WORKTREE");
            var diff=new VersionDiffService().compare(service,a.entry(),b.entry(),"MAIN","extra",false,3);
            assertTrue(diff.changes().stream().anyMatch(r->r.get("record").equals("declaration_change") && "added".equals(r.get("change"))));
        });
    }

    @Test void realCrossModuleTraversalKeepsFilteredIntermediate() throws Exception {
        isolated(()->{
            write(".anatomist/config.toml","[scan]\nsource_roots = [\"api@MAIN=src/main/java\", \"bridge@MAIN=bridge/src/main/java\", \"tests@TEST=tests/src/test/java\"]\n");
            write("src/main/java/p/I.java","package p; public interface I { int value(); }");
            write("src/main/java/p/A.java","package p; public class A implements I { public int value() { return 1; } }");
            write("bridge/src/main/java/p/Bridge.java","package p; public class Bridge { public int call(I value) { return value.value(); } }");
            write("tests/src/test/java/p/Check.java","package p; public class Check { public int check() { return new Bridge().call(new A()); } }");commit("cross module");var a=build("HEAD");
            write("src/main/java/p/A.java","package p; public class A implements I { public int value() { return 2; } }");var b=build("WORKTREE");
            var diff=new VersionDiffService().compare(service,a.entry(),b.entry(),"MAIN","api",true,3,"TEST","tests");
            var impacts=diff.changes().stream().filter(r->r.get("record").equals("impact")).toList();
            assertEquals(2,impacts.size(),diff.json().toString());
            assertTrue(impacts.stream().allMatch(r->Boolean.TRUE.equals(r.get("contains_possible_dispatch"))));
            assertTrue(impacts.stream().allMatch(r->((List<?>)r.get("path")).size()==3));
        });
    }

    @Test void corruptFrozenSourceKeepsManifestButCannotClaimDeclarationCoverage() throws Exception {
        isolated(()->{
            var a=build("HEAD");write("src/main/java/p/A.java","package p; public class A { public int value() { return 2; } }");var b=build("WORKTREE");
            try(var query=new QueryService(b.database())) { Files.writeString(com.anatomist.query.SnapshotSource.path(query.connection(),"src/main/java/p/A.java"),"corrupt"); }
            var diff=new VersionDiffService().compare(service,a.entry(),b.entry(),"MAIN",null,false,3);
            assertEquals(false,capability(diff,"declarations").get("complete"));
            assertTrue(((List<?>)capability(diff,"declarations").get("target_reasons")).contains("SNAPSHOT_SOURCE_CORRUPT"));
            assertEquals(true,capability(diff,"files").get("complete"));
            assertFalse(diff.changes().stream().anyMatch(r->r.get("record").equals("declaration_change")));
        });
    }
    @Test void polymorphicImpactKeepsVersionAnchorsAndViewDoesNotChangeAnalysis() throws Exception {
        isolated(()->{
            write("src/main/java/p/I.java","package p; public interface I { int work(); }");
            write("src/main/java/p/Worker.java","package p; public class Worker implements I { public int work() { return 1; } }");
            write("src/main/java/p/Caller.java","package p; public class Caller { public int call(I i) { return i.work(); } }");
            commit("polymorphic callers");var a=build("HEAD");
            write("src/main/java/p/Worker.java","package p; public class Worker implements I { public int work() { return 2; } }");var b=build("WORKTREE");
            var diff=new VersionDiffService().compare(service,a.entry(),b.entry(),"MAIN",null,true,3);
            var impacts=diff.changes().stream().filter(r->r.get("record").equals("impact")).toList();
            assertEquals(2,impacts.size(),diff.json().toString());
            assertTrue(impacts.stream().allMatch(r->Boolean.TRUE.equals(r.get("contains_possible_dispatch"))));
            assertTrue(impacts.stream().allMatch(r->r.get("entity").toString().endsWith("p.Caller#call(p.I)")));
            assertFalse(diff.changes().stream().anyMatch(r->r.get("record").equals("relation_change")));
            var resolved=new VersionDiffService().compare(service,a.entry(),b.entry(),"MAIN",null,true,3,"MAIN",null,"resolved");
            assertFalse(resolved.changes().stream().anyMatch(r->r.get("record").equals("impact")));
            var focused=diff.present("calls",Map.of());
            assertEquals(impacts,focused.changes().stream().filter(r->r.get("record").equals("impact")).toList());
            assertEquals(capability(diff,"impact"),capability(focused,"impact"));
            assertEquals(false,capability(diff,"impact").get("truncated"));
            assertEquals(false,capability(diff,"impact").get("negative_conclusion_safe"));
            assertTrue(focused.changes().stream().noneMatch(r->r.get("record").equals("file_change")));
            assertEquals(diff.changes().size()-focused.changes().size(),((Map<?,?>)focused.header().get("output")).get("hidden"));
            var noImpact=new VersionDiffService().compare(service,a.entry(),b.entry(),"MAIN",null,false,3).present("calls",Map.of());
            assertEquals("not_requested",capability(noImpact,"impact").get("status"));
            assertTrue(noImpact.changes().stream().anyMatch(r->r.get("record").equals("declaration_change")));
            write("src/main/java/p/Worker.java","package p; public class Worker { public int work() { return 3; } }");
            var detached=build("WORKTREE");
            var changedHierarchy=new VersionDiffService().compare(service,a.entry(),detached.entry(),"MAIN",null,true,3);
            var workerImpacts=changedHierarchy.changes().stream().filter(r->r.get("record").equals("impact")
                    && ((Map<?,?>)r.get("origin")).get("symbol").equals("p.Worker#work()")).toList();
            assertEquals(1,workerImpacts.size(),changedHierarchy.json().toString());
            assertEquals("base",workerImpacts.getFirst().get("side"));
        });
    }

    @Test void callsViewKeepsCallRetargetingAndHidesOtherRelationships() throws Exception {
        isolated(()->{
            write("src/main/java/p/A.java","package p; public class A { public int value() { return 1; } public int other() { return 9; } }");
            write("src/main/java/p/B.java","package p; public class B { public int call() { return new A().value(); } }");
            commit("caller");var a=build("HEAD");
            write("src/main/java/p/B.java","package p; @Deprecated public class B { public int call() { return new A().other(); } }");var b=build("WORKTREE");
            var all=new VersionDiffService().compare(service,a.entry(),b.entry(),"MAIN",null,true,3);
            var calls=all.present("calls",Map.of());
            assertTrue(all.changes().stream().anyMatch(r->r.get("record").equals("relation_change") && !((Map<?,?>)r.get("relationship")).get("relation").equals("CALLS")));
            assertTrue(calls.changes().stream().anyMatch(r->r.get("record").equals("relation_change") && r.get("change").equals("added")));
            assertTrue(calls.changes().stream().anyMatch(r->r.get("record").equals("relation_change") && r.get("change").equals("deleted")));
            assertTrue(calls.changes().stream().filter(r->r.get("record").equals("relation_change")).allMatch(r->((Map<?,?>)r.get("relationship")).get("relation").equals("CALLS")));
            assertEquals(all.changes().stream().filter(r->r.get("record").equals("impact")).toList(),calls.changes().stream().filter(r->r.get("record").equals("impact")).toList());
        });
    }

    @Test void noBuildWorktreeMergeBaseUsesTheCapturedCommitAfterBranchSwitch() throws Exception {
        isolated(()->{
            String ancestor=service.git().commit("HEAD");build("HEAD");
            write("left.txt","left");commit("left");String left=service.git().commit("HEAD");build("HEAD");
            var capture=build("WORKTREE");
            GitRepository.text(project,"checkout","--detach",ancestor);
            write("right.txt","right");commit("right");String right=service.git().commit("HEAD");
            var output=(Map<?,?>)Json.parseTree(cli("diff","--project",project.toString(),"--base",left,
                    "--target","WORKTREE","--merge-base","--no-build","--format","json"));
            var header=(Map<?,?>)output.get("comparison");
            assertEquals(left,((Map<?,?>)header.get("base")).get("commit"));
            assertEquals(capture.entry().id(),((Map<?,?>)header.get("target")).get("id"));
            assertEquals(left,((Map<?,?>)((Map<?,?>)header.get("request")).get("target")).get("commit"));
            assertEquals(right,service.git().commit("HEAD"));
        });
    }

}
