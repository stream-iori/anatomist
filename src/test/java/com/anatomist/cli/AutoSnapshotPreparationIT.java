package com.anatomist.cli;

import com.anatomist.application.SnapshotService;
import com.anatomist.json.Json;
import com.anatomist.store.SnapshotCatalog;
import com.anatomist.version.GitRepository;
import com.anatomist.version.SnapshotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("system-properties")
class AutoSnapshotPreparationIT {
    @TempDir Path temporary;
    Path project;
    SnapshotService service;
    String base, target;
    interface Work { void run() throws Exception; }
    void isolated(Work work) throws Exception {
        String previous=System.getProperty("user.home");
        System.setProperty("user.home",temporary.resolve("home").toString());
        try {
            assertEquals(temporary.resolve("home/.anatomist"),com.anatomist.config.StoragePaths.home(),
                    "Run with ANATOMIST_HOME unset; every fixture must own its store");
            project=Files.createDirectories(temporary.resolve("project")).toRealPath();
            write("pom.xml","<project><modelVersion>4.0.0</modelVersion><groupId>p</groupId><artifactId>sample</artifactId><version>1</version></project>");
            write(".anatomist/config.toml","[scan]\nscopes = [\"MAIN\", \"GENERATED\"]\n");
            write("src/main/java/p/A.java","package p; public class A { public static int value() { return 1; } }");
            write("src/test/java/p/Check.java","package p; public class Check { public int check() { return A.value(); } }");
            git("init","-q");git("config","user.name","Snapshot Test");git("config","user.email","snapshot@example.test");
            commit();base=git("rev-parse","HEAD");
            write("src/main/java/p/A.java","package p; public class A { public static int value() { return 2; } }");
            commit();target=git("rev-parse","HEAD");service=new SnapshotService(project);
            work.run();
        } finally { System.setProperty("user.home",previous); }
    }
    void write(String name,String value) throws Exception {
        Path file=project.resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,value);
    }
    String git(String... args) { return GitRepository.text(project,args); }
    void commit() { git("add",".");git("commit","-qm","fixture"); }
    record Output(int exit,String out,String err) {
        Map<?,?> json() { return (Map<?,?>)Json.parseTree(exit==0?out:err.lines().filter(s->s.startsWith("{")).reduce((a,b)->b).orElseThrow()); }
    }
    Output cli(String... args) {
        PrintStream out=System.out,err=System.err;
        var stdout=new ByteArrayOutputStream();var stderr=new ByteArrayOutputStream();
        try(var a=new PrintStream(stdout);var b=new PrintStream(stderr)) {
            System.setOut(a);System.setErr(b);
            int exit=AnatomistCli.commandLine(args).execute(args);
            return new Output(exit,stdout.toString(),stderr.toString());
        } finally { System.setOut(out);System.setErr(err); }
    }
    Output diff(String... extra) {
        var args=new ArrayList<>(List.of("diff","--project",project.toString(),"--base",base,"--target",target,
                "--no-classpath","--java-version","25","--scope","ALL","--impact-scope","ALL","--impact","--format","json"));
        args.addAll(List.of(extra));return cli(args.toArray(String[]::new));
    }
    String snapshot(String ref,String... extra) {
        var args=new ArrayList<>(List.of("index",project.toString(),"--ref",ref,"--no-classpath","--java-version","25","--format","json"));
        args.addAll(List.of(extra));var result=cli(args.toArray(String[]::new));assertEquals(0,result.exit(),result.err());return (String)result.json().get("id");
    }
    Map<?,?> comparison(Output output) { assertEquals(0,output.exit(),output.err());return (Map<?,?>)output.json().get("comparison"); }
    long testCallers(Output output) {
        return ((List<?>)output.json().get("changes")).stream().map(r->(Map<?,?>)r)
                .filter(r->"impact".equals(r.get("record")) && "TEST".equals(((Map<?,?>)r.get("caller")).get("scope"))).count();
    }
    @Test void includesTestsAndSelectsMatchingConfigurationAmongProfiles() throws Exception {
        isolated(()->{
            var main=diff();comparison(main);assertEquals(0,testCallers(main));
            var tests=diff("--include-tests");var expected=comparison(tests);assertEquals(2,testCallers(tests));
            assertNotEquals(comparison(main).get("base"),expected.get("base"));
            // A newer, unrelated profile must not shadow the requested capture.
            snapshot(base,"--spring-xml");snapshot(target,"--spring-xml");
            assertEquals(expected,comparison(diff("--include-tests","--no-build")));
            assertEquals(expected,comparison(diff("--include-tests")));
            assertEquals(target,git("rev-parse","HEAD"));assertEquals("",git("status","--porcelain"));
        });
    }
    @Test void noBuildDiagnosesMissingAndMismatchedCapturesWithoutBuilding() throws Exception {
        isolated(()->{
            assertEquals("SNAPSHOT_MISSING",diff("--no-build","--include-tests").json().get("code"));
            assertFalse(Files.exists(service.directory().resolve("catalog.db")));
            snapshot(base);snapshot(target);
            int count;try(var catalog=new SnapshotCatalog(service.directory())) { count=catalog.list().size(); }
            var failure=diff("--no-build","--include-tests");
            assertEquals("SNAPSHOT_CONFIG_MISMATCH",failure.json().get("code"));
            assertEquals("base",((Map<?,?>)failure.json().get("details")).get("side"));
            try(var catalog=new SnapshotCatalog(service.directory())) { assertEquals(count,catalog.list().size()); }
            assertFalse(Files.exists(service.directory().resolve("workspace")));
        });
    }
    @Test void explicitSnapshotsStayFixedAndRejectUnsatisfiedCoverage() throws Exception {
        isolated(()->{
            base="snapshot:"+snapshot(base);target="snapshot:"+snapshot(target);
            assertEquals("SNAPSHOT_COVERAGE_MISMATCH",diff("--include-tests").json().get("code"));
            assertEquals(base.substring(9),((Map<?,?>)comparison(diff()).get("base")).get("id"));
            assertEquals(base.substring(9),((Map<?,?>)comparison(diff("--merge-base")).get("base")).get("id"));
        });
    }
    @Test void explicitRootConflictFailsAndCleansPrivateWorktree() throws Exception {
        isolated(()->{
            write(".anatomist/config.toml","[scan]\nsource_roots = [\"sample@MAIN=src/main/java\"]\n");
            commit();base=git("rev-parse","HEAD");target=base;
            assertEquals("SNAPSHOT_COVERAGE_MISMATCH",diff("--include-tests").json().get("code"));
            assertFalse(Files.exists(service.directory().resolve("workspace")));
            try(var catalog=new SnapshotCatalog(service.directory())) {
                assertTrue(catalog.list().stream().allMatch(e->e.status().equals("FAILED")));
                for(var entry:catalog.list()) assertFalse(Files.exists(service.database(entry.id())));
            }
            assertEquals(1,git("worktree","list","--porcelain").lines().filter(s->s.startsWith("worktree ")).count());
        });
    }
    @Test void linkedWorktreeReusesMatchingCommitSnapshots() throws Exception {
        isolated(()->{
            var expected=comparison(diff("--include-tests"));
            Path original=project,linked=temporary.resolve("linked");git("worktree","add","--detach",linked.toString(),target);
            try { project=linked;assertEquals(expected,comparison(diff("--include-tests","--no-build"))); }
            finally { project=original;git("worktree","remove","--force",linked.toString()); }
        });
    }
    @Test void missingDatabaseDoesNotCreateAnEmptyReplacementDuringLookup() throws Exception {
        isolated(()->{
            String a=snapshot(base,"--include-tests");snapshot(target,"--include-tests");
            Files.delete(service.database(a));
            assertEquals("SNAPSHOT_NOT_READY",diff("--include-tests","--no-build").json().get("code"));
            assertFalse(Files.exists(service.database(a)));
            assertEquals(2,testCallers(diff("--include-tests")));
        });
    }
    @Test void frozenCommitAndExpectedWorktreeHeadCannotDrift() throws Exception {
        isolated(()->{
            var request=new DiffCommand();request.project=project;request.noClasspath=true;request.javaVersion=25;
            var options=request.snapshotOptions();
            git("branch","moving",base);String frozen=service.commit("moving");
            var built=service.build(frozen,Json.writeCompact(options),false,(root,db,incremental)->{
                git("update-ref","refs/heads/moving",target);
                return IndexCommand.snapshotBuilder(options,project).build(root,db,incremental);
            },frozen);
            assertEquals(base,built.entry().commit());assertEquals(target,service.commit("moving"));
            var failure=assertThrows(SnapshotException.class,()->service.build("WORKTREE",Json.writeCompact(options),false,
                    (root,db,incremental)->{fail("builder must not run");return Map.of();},base));
            assertEquals("WORKTREE_CHANGED",failure.code());
        });
    }
    @Test void invalidNewerCandidatesDoNotHideUsableMatchingSnapshots() throws Exception {
        isolated(()->{
            String usable=snapshot(base,"--include-tests");snapshot(target,"--include-tests");
            String invalid=snapshot(base,"--include-tests","--full");
            String unrelated=snapshot(base,"--spring-xml");
            Files.delete(service.database(invalid));Files.delete(service.database(unrelated));
            var result=comparison(diff("--include-tests","--no-build"));
            assertEquals(usable,((Map<?,?>)result.get("base")).get("id"));
            assertFalse(Files.exists(service.database(invalid)));
            assertFalse(Files.exists(service.database(unrelated)));
        });
    }
    @Test void mergeBaseUsesCommitForImplicitWorktreeButPreservesExplicitInstance() throws Exception {
        isolated(()->{
            snapshot(target,"--include-tests");
            write("src/main/java/p/A.java","package p; public class A { public static int value() { return 3; } }");
            String dirty=snapshot("WORKTREE","--include-tests");
            base="WORKTREE";
            var implicit=comparison(diff("--include-tests","--no-build","--merge-base"));
            assertNotEquals(dirty,((Map<?,?>)implicit.get("base")).get("id"));
            base="snapshot:"+dirty;
            var explicit=comparison(diff("--include-tests","--no-build","--merge-base"));
            assertEquals(dirty,((Map<?,?>)explicit.get("base")).get("id"));
        });
    }
    @Test void explicitTestRootsUseTheIndexScopeParser() throws Exception {
        isolated(()->{
            write(".anatomist/config.toml","[scan]\nsource_roots = [\"sample@main=src/main/java\", \"sample@test=src/test/java\"]\n");
            commit();base=git("rev-parse","HEAD");target="WORKTREE";
            write("src/main/java/p/A.java","package p; public class A { public static int value() { return 3; } }");
            var result=diff("--include-tests");comparison(result);assertEquals(2,testCallers(result));
        });
    }
    @Test void capturedBuildOutputsSurviveCleanupWhileExternalDependenciesAreChecked() throws Exception {
        isolated(()->{
            var command=new DiffCommand();command.project=project;command.noClasspath=true;command.javaVersion=25;
            var options=command.snapshotOptions();String request=Json.writeCompact(options);
            Path external=temporary.resolve("dependency.jar");Files.writeString(external,"v1");
            var built=service.build("WORKTREE",request,false,(root,db,incremental)->{
                var metrics=IndexCommand.snapshotBuilder(options,project).build(root,db,incremental);
                Path classes=Files.createDirectories(root.resolve("target/classes"));Files.writeString(classes.resolve("Generated.class"),"captured bytes");
                try(var store=new com.anatomist.store.SqliteStore(db)) {
                    store.upsertProjectMeta("classpath_entries",classes.toRealPath()+File.pathSeparator+external);
                }
                return metrics;
            });
            assertFalse(Files.exists(service.directory().resolve("workspace")));
            assertEquals(built.entry().id(),service.resolve("WORKTREE",request).id());
            assertTrue(service.build("WORKTREE",request,false,(root,db,incremental)->{
                fail("unchanged capture should be reused");return Map.of();
            }).reused());
            Files.writeString(external,"v2");
            assertEquals("SNAPSHOT_NOT_READY",assertThrows(SnapshotException.class,()->service.resolve("WORKTREE",request)).code());
            assertEquals(built.entry().id(),service.resolve("snapshot:"+built.entry().id()).id());
        });
    }
    @Test void incrementalCaptureVerifiesContentEvenWhenFileStatsMatch() throws Exception {
        isolated(()->{
            var command=new DiffCommand();command.project=project;command.noClasspath=true;command.javaVersion=25;
            var options=command.snapshotOptions();String request=Json.writeCompact(options);
            SnapshotService.Builder fixedTimes=(root,db,incremental)->{
                try(var files=Files.walk(root)) {
                    for(Path file:files.filter(p->p.toString().endsWith(".java")).toList())
                        Files.setLastModifiedTime(file,java.nio.file.attribute.FileTime.fromMillis(1700000000000L));
                }
                return IndexCommand.snapshotBuilder(options,project).build(root,db,incremental);
            };
            var first=service.build("WORKTREE",request,false,fixedTimes);
            write("src/main/java/p/A.java","package p; public class A { public static int value() { return 3; } }");
            var second=service.build("WORKTREE",request,false,fixedTimes);
            assertNotEquals(first.entry().sourceSnapshot(),second.entry().sourceSnapshot());
            assertNotEquals(first.entry().id(),second.entry().id());
            assertEquals(first.entry().id(),((Map<?,?>)Json.parseTree(second.entry().metrics())).get("baseline"));
        });
    }
    @Test void ignoredReportsReuseCaptureButRequiredIgnoredSourcesInvalidateIt() throws Exception {
        isolated(()->{
            write(".gitignore","target/\nsrc/main/java/p/Ignored.java\n.anatomist/index.db*\n");commit();
            write("src/main/java/p/Ignored.java","package p; class Ignored { int value() { return 1; } }");
            target="WORKTREE";
            var first=comparison(diff("--timings"));String id=(String)((Map<?,?>)first.get("target")).get("id");
            write("target/report.txt","not a source input");write(".anatomist/index.db","local index bytes");
            var repeat=comparison(diff("--timings"));assertEquals(id,((Map<?,?>)repeat.get("target")).get("id"));
            var files=(Map<?,?>)Json.parseTree(Files.readString(service.snapshotDirectory(id).resolve("files.json")));
            assertTrue(files.containsKey("src/main/java/p/Ignored.java"));assertFalse(files.containsKey("target/report.txt"));
            assertFalse(files.containsKey(".anatomist/index.db"));
            write("src/main/java/p/Ignored.java","package p; class Ignored { int value() { return 2; } }");
            assertNotEquals(id,((Map<?,?>)comparison(diff()).get("target")).get("id"));
        });
    }
    @Test void ignoredGeneratedSourcesAndXmlRemainAvailable() throws Exception {
        isolated(()->{
            write(".gitignore","target/\nsrc/main/resources/\n");
            write(".anatomist/config.toml","[index]\nspring_xml = true\n");commit();
            write("target/generated-sources/probe/p/Generated.java","package p; class Generated {}");
            write("src/main/resources/beans.xml","<beans xmlns=\"http://www.springframework.org/schema/beans\"><bean id=\"a\" class=\"p.A\"/></beans>");
            target="WORKTREE";
            String id=(String)((Map<?,?>)comparison(diff()).get("target")).get("id");
            var files=(Map<?,?>)Json.parseTree(Files.readString(service.snapshotDirectory(id).resolve("files.json")));
            assertTrue(files.containsKey("target/generated-sources/probe/p/Generated.java"));
            assertTrue(files.containsKey("src/main/resources/beans.xml"));
        });
    }
    @Test void gcRecoversInterruptedBuildAndMissingRegisteredWorkspace() throws Exception {
        isolated(()->{
            comparison(diff());
            String id="f".repeat(32);Path work=service.directory().resolve("workspace");
            com.anatomist.application.SnapshotRecovery.claim(service);git("worktree","add","--detach",work.toString(),target);
            try(var catalog=new SnapshotCatalog(service.directory())) { catalog.begin(id,target,"","broken","broken"); }
            Files.createDirectories(service.snapshotDirectory(id));Files.writeString(service.database(id),"partial");
            var preview=com.anatomist.application.SnapshotMaintenance.collect(service,20,false);
            assertFalse(((List<?>)preview.get("recovery")).isEmpty());assertTrue(Files.exists(work));assertTrue(Files.exists(service.database(id)));
            // The directory vanished but its Git administrative registration survived.
            com.anatomist.version.SnapshotFiles.deleteOwned(service.directory(),work);
            var executed=com.anatomist.application.SnapshotMaintenance.collect(service,20,true);
            assertFalse(((List<?>)executed.get("recovery")).isEmpty());assertFalse(Files.exists(service.database(id)));
            assertEquals(1,git("worktree","list","--porcelain").lines().filter(line->line.startsWith("worktree ")).count());
            target="WORKTREE";write("src/main/java/p/A.java","package p; public class A { public static int value() { return 9; } }");
            comparison(diff());
            assertTrue(((List<?>)com.anatomist.application.SnapshotMaintenance.collect(service,20,true).get("recovery")).isEmpty());
        });
    }
    @Test void gcDoesNotClaimUnknownWorkspaceAndReportsBudgetProtection() throws Exception {
        isolated(()->{
            String id=(String)((Map<?,?>)comparison(diff()).get("base")).get("id");
            try(var catalog=new SnapshotCatalog(service.directory())) { catalog.pin(id,true); }
            Path work=Files.createDirectories(service.directory().resolve("workspace"));Files.deleteIfExists(service.directory().resolve("workspace.owner"));
            Files.writeString(work.resolve("unknown.txt"),"preserve");
            var result=com.anatomist.application.SnapshotMaintenance.collect(service,0,1,30,false,true,Set.of());
            assertEquals(false,result.get("budget_met"));assertTrue(Files.exists(service.database(id)));assertTrue(Files.exists(work.resolve("unknown.txt")));
            assertThrows(SnapshotException.class,()->com.anatomist.application.SnapshotRecovery.claim(service));
        });
    }
    @Test void automaticGcIsOptInAndProtectsBothEndpoints() throws Exception {
        isolated(()->{
            comparison(diff());
            write(".anatomist/config.toml","[versions.gc]\nauto = true\nkeep = 0\nmax_bytes = 1\n");commit();
            base=target;target=git("rev-parse","HEAD");
            var header=comparison(diff());
            for(String side:List.of("base","target")) assertTrue(Files.exists(service.database((String)((Map<?,?>)header.get(side)).get("id"))));
            try(var catalog=SnapshotCatalog.read(service.directory())) { assertEquals(2,catalog.list().size()); }
        });
    }
    @Test void warmNoBuildDoesNotWaitForRepositoryWriter() throws Exception {
        isolated(()->{
            comparison(diff());
            try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
                var holder=executor.submit(()->{
                    try(var lock=com.anatomist.store.IndexOperationLock.forWrite(service.directory().resolve("catalog.db"))) {
                        locked.countDown();release.await();
                    }return null;
                });
                assertTrue(locked.await(5,java.util.concurrent.TimeUnit.SECONDS));
                try { org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),()->comparison(diff("--no-build"))); }
                finally { release.countDown();holder.get(); }
            }
        });
    }

    @Test void gcExpiresMovedAndUnusedEntrypointsAndMigratesOldCatalog() throws Exception {
        isolated(()->{
            git("branch","retained",base);
            String id=snapshot("retained");
            try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+service.directory().resolve("catalog.db"));var statement=connection.createStatement()) {
                statement.execute("DROP TABLE head_usage");statement.execute("DROP TABLE repository_meta");statement.execute("PRAGMA user_version=1");
            }
            assertEquals(id,service.resolve("snapshot:"+id).id());
            var retained=com.anatomist.application.SnapshotMaintenance.collect(service,0,true);
            assertEquals(0,retained.get("deleted"));assertTrue(Files.exists(service.database(id)));
            git("branch","-f","retained",target);
            var removed=com.anatomist.application.SnapshotMaintenance.collect(service,0,true);
            assertEquals(1,removed.get("deleted"));assertFalse(Files.exists(service.database(id)));
            String current=snapshot("retained");
            try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+service.directory().resolve("catalog.db"));var statement=connection.createStatement()) {
                statement.execute("UPDATE head_usage SET selected='2000-01-01T00:00:00Z'");
            }
            assertEquals(1,com.anatomist.application.SnapshotMaintenance.collect(service,0,true).get("deleted"));
            assertFalse(Files.exists(service.database(current)));
        });
    }

    @Test void stagedDeletionDoesNotHideTrackedDiskBytes() throws Exception {
        isolated(()->{
            write("tracked.txt","disk content");commit();write(".gitignore","tracked.txt\n");
            git("rm","--cached","tracked.txt");
            String id=snapshot("WORKTREE");
            var manifest=(Map<?,?>)Json.parseTree(Files.readString(service.snapshotDirectory(id).resolve("files.json")));
            assertTrue(manifest.containsKey("tracked.txt"));
            assertEquals("disk content",Files.readString(project.resolve("tracked.txt")));
            assertTrue(git("diff","--cached","--name-only").contains("tracked.txt"));
        });
    }

    @Test void requiredInputsCannotEscapeThroughSymbolicParent() throws Exception {
        isolated(()->{
            Path outside=Files.createDirectories(temporary.resolve("outside"));
            Files.writeString(outside.resolve("Generated.java"),"class Generated {}");
            write(".gitignore","generated/\n");
            Files.createSymbolicLink(project.resolve("generated"),outside);
            var failure=assertThrows(SnapshotException.class,()->com.anatomist.version.SnapshotCapture.inventory(
                    project,List.of(project.resolve("generated/Generated.java"))));
            assertEquals("SNAPSHOT_SYMLINK_UNSUPPORTED",failure.code());
        });
    }

    @Test void requiredBuildArtifactsAreFrozenWithoutBecomingSourceBlobs() throws Exception {
        isolated(()->{
            Path classes=project.resolve("target/classes");write("target/classes/A.class","artifact one");
            var command=new DiffCommand();command.project=project;command.noClasspath=true;command.javaVersion=25;
            var options=command.snapshotOptions();var delegate=IndexCommand.snapshotBuilder(options,project);
            SnapshotService.Builder builder=new SnapshotService.Builder() {
                public Collection<Path> inputs(Path root) throws Exception { return delegate.inputs(root); }
                public Collection<Path> artifacts(Path root) { return List.of(root.resolve("target/classes")); }
                public Map<String,Object> build(Path root,Path db,boolean incremental) throws Exception {
                    assertEquals(Files.readString(classes.resolve("A.class")),Files.readString(root.resolve("target/classes/A.class")));
                    return delegate.build(root,db,incremental);
                }
            };
            var first=service.build("WORKTREE",Json.writeCompact(options),false,builder);
            var manifest=(Map<?,?>)Json.parseTree(Files.readString(service.snapshotDirectory(first.entry().id()).resolve("files.json")));
            assertFalse(manifest.containsKey("target/classes/A.class"));
            write("target/classes/A.class","artifact two");
            assertNotEquals(first.entry().id(),service.build("WORKTREE",Json.writeCompact(options),false,builder).entry().id());
        });
    }

}
