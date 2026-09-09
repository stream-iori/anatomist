package com.anatomist.application;

import com.anatomist.json.Json;
import com.anatomist.query.semantic.SemanticIdentity;
import com.anatomist.store.*;
import com.anatomist.version.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Coordinates immutable version builds outside the user's checkout. */
public final class SnapshotService implements SnapshotAccess {
    public interface Builder {
        Map<String,Object> build(Path project, Path database, boolean incremental) throws Exception;
        default Collection<Path> inputs(Path project) throws Exception { return List.of(); }
        default Collection<Path> artifacts(Path project) throws Exception { return List.of(); }
    }
    private boolean operationActive;
    public void beginOperation() { validatedArtifacts.clear();operationMetrics.clear();operationActive=true; }
    private final Map<Path,String> validatedArtifacts=new HashMap<>();
    private final Map<String,Long> operationMetrics=new LinkedHashMap<>();
    public Map<String,Long> operationMetrics() { return Map.copyOf(operationMetrics); }
    private void metric(String name,long value) { operationMetrics.merge(name,value,Long::sum); }
    private final GitRepository git;
    private final Path directory;
    public SnapshotService(Path project) {
        git = GitRepository.open(project);
        directory = com.anatomist.config.StoragePaths.home().resolve("versions").resolve(git.key()).toAbsolutePath().normalize();
    }
    public GitRepository git() { return git; }
    public Path directory() { return directory; }
    public Path database(String id) { return snapshotDirectory(id).resolve("index.db"); }
    public Path snapshotDirectory(String id) {
        if (!id.matches("[a-f0-9]{32}")) throw new SnapshotException("SNAPSHOT_INVALID", "Invalid snapshot id: " + id);
        return directory.resolve("snapshots").resolve(id);
    }
    private String selectorKey(String ref) {
        return ref.equals("WORKTREE") || ref.equals("HEAD") ? ref+":"+git.checkoutKey() : ref;
    }
    public String commit(String ref) {
        if(ref.startsWith("snapshot:")) return entry(ref.substring(9)).commit();
        return git.commit(ref.equals("WORKTREE") ? "HEAD" : ref);
    }
    public SnapshotCatalog.Entry entry(String id) {
        requireCatalog();
        try(var catalog=SnapshotCatalog.read(directory)) { return catalog.get(id); }
    }
    public SnapshotCatalog.Entry resolve(String ref) {
        requireCatalog();
        try(var catalog=SnapshotCatalog.read(directory)) {
            if(ref.startsWith("snapshot:")) return requireReady(catalog.get(ref.substring(9)));
            List<SnapshotCatalog.Entry> entries=(ref.equals("WORKTREE") ? catalog.forSelector(selectorKey(ref)) : catalog.forCommit(commit(ref))).stream()
                    .filter(e->e.status().equals("READY")).toList();
            if(entries.isEmpty()) throw new SnapshotException("SNAPSHOT_MISSING",
                    "No snapshot for " + ref + "; run anatomist index " + git.project() + " --ref " + ref);
            if(entries.stream().map(SnapshotCatalog.Entry::profile).distinct().count()>1)
                throw new SnapshotException("SNAPSHOT_AMBIGUOUS","Multiple profiles for " + ref + "; select --snapshot <id>");
            return requireReady(entries.getFirst());
        }
    }
    /** Diff selection uses the same request fingerprint as immutable builds. Never builds. */
    public SnapshotCatalog.Entry resolve(String ref, String request) {
        if(!operationActive) validatedArtifacts.clear();
        String sha=ref.equals("WORKTREE") ? "" : commit(ref);
        Map<String,Object> details=new LinkedHashMap<>();
        details.put("selector",ref); details.put("commit",sha);
        details.put("requested_options",Json.parseTree(request));
        details.put("candidate_ids",List.of());
        if(!Files.isRegularFile(directory.resolve("catalog.db")))
            throw new SnapshotException("SNAPSHOT_MISSING","No version catalog",details);
        try(var catalog=SnapshotCatalog.read(directory)) {
            String hash=requestHash(request);
            var candidates=ref.equals("WORKTREE")?catalog.forSelector(selectorKey(ref)):catalog.forCommit(sha);
            details.put("candidate_ids",candidates.stream().map(SnapshotCatalog.Entry::id).toList());
            var matching=candidates.stream().filter(e->e.requestHash().equals(hash)).toList();
            for(var entry:matching) if(entry.status().equals("READY") && artifactsCurrent(database(entry.id())))
                return requireReady(entry);
            String code=candidates.isEmpty()?"SNAPSHOT_MISSING":matching.isEmpty()?"SNAPSHOT_CONFIG_MISMATCH":"SNAPSHOT_NOT_READY";
            throw new SnapshotException(code,"No usable snapshot matching the requested indexing configuration",details);
        } catch(SnapshotException failure) { throw failure; }
        catch(Exception failure) { throw new SnapshotException("CATALOG_FAILED",failure.getMessage(),failure); }
    }

    /** Explicit instances retain their identity; only explicitly requested requirements apply. */
    public SnapshotCatalog.Entry requireOptions(SnapshotCatalog.Entry entry, boolean tests, Integer javaVersion, boolean noClasspath) {
        requireReady(entry);
        try(SqliteStore store=new SqliteStore(database(entry.id()))) {
            String policy=store.readProjectMeta("scan_policy").orElse("");
            boolean testCoverage=policy.lines().anyMatch(s->s.equals("scope=TEST") || s.startsWith("root=") && s.contains("@TEST="));
            if(tests && !testCoverage) throw new SnapshotException("SNAPSHOT_COVERAGE_MISMATCH",
                    "TEST coverage is required; configure TEST source roots or capture with --include-tests",
                    Map.of("snapshot_id",entry.id(),"required_scope","TEST","reason","TEST_COVERAGE_NOT_CONFIRMED"));
            if(javaVersion!=null && !javaVersion.toString().equals(store.readProjectMeta("java_version").orElse(""))
                    || noClasspath && !"none".equals(store.readProjectMeta("classpath_mode").orElse("")))
                throw new SnapshotException("SNAPSHOT_CONFIG_MISMATCH","Explicit snapshot does not satisfy requested options",
                        Map.of("snapshot_id",entry.id(),"reason","EXPLICIT_OPTIONS_DIFFER"));
            return entry;
        }
    }

    private void requireCatalog() {
        if(!Files.isRegularFile(directory.resolve("catalog.db"))) throw new SnapshotException("SNAPSHOT_MISSING",
                "No version catalog; run anatomist index " + git.project() + " --ref HEAD");
    }
    private SnapshotCatalog.Entry requireReady(SnapshotCatalog.Entry entry) {
        if(!entry.status().equals("READY") || !Files.isRegularFile(database(entry.id())))
            throw new SnapshotException("SNAPSHOT_NOT_READY","Snapshot is not available: " + entry.id());
        return entry;
    }
    public record Built(SnapshotCatalog.Entry entry, Path database, boolean reused) {
        public Map<String,Object> json() {
            Map<String,Object> out=new LinkedHashMap<>(entry.json());
            out.put("command","index"); out.put("index",database.toString()); out.put("reused",reused); return out;
        }
    }
    public Built build(String ref,String request,boolean full,Builder builder) {
        return build(ref,request,full,builder,null);
    }
    public Built build(String ref,String request,boolean full,Builder builder,String expectedCommit) {
        if(!operationActive) validatedArtifacts.clear();
        if(ref.startsWith("snapshot:")) throw new IllegalArgumentException("Build requires a Git ref or WORKTREE");
        String sha=commit(ref);
        if(expectedCommit!=null && !expectedCommit.equals(sha))
            throw new SnapshotException("WORKTREE_CHANGED","HEAD changed after endpoint selection");
        if(!full && ref.matches("[a-fA-F0-9]{40,64}") && Files.isRegularFile(directory.resolve("catalog.db"))) {
            try { var cached=resolve(ref,request); metric("snapshot_hits",1);return new Built(cached,database(cached.id()),true); }
            catch(SnapshotException unavailable) {
                if(!Set.of("SNAPSHOT_MISSING","SNAPSHOT_CONFIG_MISMATCH","SNAPSHOT_NOT_READY").contains(unavailable.code())) throw unavailable;
            }
        }
        Path catalogPath=directory.resolve("catalog.db"), workspace=directory.resolve("workspace");
        try {
            Path existingParent=directory;
            while(!Files.exists(existingParent)) existingParent=existingParent.getParent();
            Path canonicalDirectory=existingParent.toRealPath().resolve(existingParent.relativize(directory));
            if(canonicalDirectory.startsWith(git.root())) throw new SnapshotException("SNAPSHOT_STORAGE_INVALID",
                    "ANATOMIST_HOME must be outside the Git working tree for version indexing");
            Files.createDirectories(directory);
            long waiting=System.nanoTime();
            try(var operation=IndexOperationLock.forWrite(catalogPath); var catalog=new SnapshotCatalog(directory)) {
                metric("lock_wait_ms",(System.nanoTime()-waiting)/1_000_000);
                catalog.metadata("common_directory",git.commonDirectory().toString());
                catalog.metadata("project_relative",git.projectRelative().toString());
                SnapshotRecovery.recover(this,catalog,true);
                boolean working=ref.equals("WORKTREE");
                String requestHash=requestHash(request);
                Map<String,String> workingFiles=null,workingArtifacts=Map.of();
                if(working) {
                    git.requireNoConflicts(); workingFiles=captureInventory(git.root(),git.project(),builder);workingArtifacts=artifactInventory(builder);
                    if(!sha.equals(git.commit("HEAD"))) throw new SnapshotException("WORKTREE_CHANGED","HEAD changed during capture");
                }
                String input=working ? FileCacheService.sha256OfString(SnapshotFiles.fingerprint(workingFiles)+"\n"+SnapshotFiles.fingerprint(workingArtifacts)) : sha;
                if(!full) for(var entry:catalog.candidates(sha,requestHash)) {
                    if(entry.status().equals("READY") && entry.commit().equals(sha)
                            && entry.checkout().equals(working?git.checkoutKey():"") && entry.inputHash().equals(input)
                            && entry.requestHash().equals(requestHash) && artifactsCurrent(database(entry.id()))) {
                        catalog.origin(selectorKey(ref),sha,entry.id());
                        metric("snapshot_hits",1);return new Built(entry,database(entry.id()),true);
                    }
                }
                metric("snapshot_builds",1);
                String id=UUID.randomUUID().toString().replace("-","");
                Path owned=snapshotDirectory(id),db=database(id);
                catalog.begin(id,sha,working?git.checkoutKey():"",requestHash,input);
                Files.createDirectories(owned);
                long started=System.nanoTime();
                boolean materialized=false;
                Exception primary=null;
                boolean published=false;
                try {
                    SnapshotRecovery.claim(this);
                    long materializeStarted=System.nanoTime();
                    git.materialize(sha,workspace); materialized=true;
                    long materializeMs=(System.nanoTime()-materializeStarted)/1_000_000;metric("materialize_ms",materializeMs);metric("materializations",1);
                    if(working) {
                        // Preserve the private Git administrative file while freezing disk contents.
                        Map<String,String> committed=SnapshotFiles.inventory(workspace);
                        for(String path:committed.keySet()) if(SnapshotFiles.resolve(workspace,path).startsWith(workspace.resolve(git.projectRelative())) && !workingFiles.containsKey(path)) Files.delete(SnapshotFiles.resolve(workspace,path));
                        captureWorkingTree(workspace,workingFiles,builder);
                        SnapshotFiles.copy(git.root(),workspace,workingArtifacts);
                        if(!workingArtifacts.equals(artifactInventory(builder))) throw new SnapshotException("WORKTREE_CHANGED","Build outputs changed during capture");
                        if(!sha.equals(git.commit("HEAD"))) throw new SnapshotException("WORKTREE_CHANGED","HEAD changed during capture");
                    }
                    Path project=workspace.resolve(git.projectRelative());
                    if(!Files.isDirectory(project)) throw new SnapshotException("PROJECT_MISSING_AT_REF","Project does not exist at " + ref);
                    Map<String,String> before=inventory(project,builder.inputs(project),builder.artifacts(project));
                    SnapshotCatalog.Entry baseline=full?null:baseline(catalog,sha,working,requestHash);
                    long copyStarted=System.nanoTime();
                    if(baseline!=null) {
                        try(IndexLock read=IndexLock.forRead(database(baseline.id()));
                            Connection connection=DriverManager.getConnection("jdbc:sqlite:"+database(baseline.id()));
                            BackupProgress progress=new BackupProgress(System.err)) {
                            int result=((org.sqlite.SQLiteConnection)connection).getDatabase().backup("main",db.toString(),progress);
                            if(result!=0) throw new SnapshotException("SNAPSHOT_COPY_FAILED","SQLite backup returned " + result);
                            progress.complete();
                        }
                        // The private build root is canonical for every linked checkout of this project.
                        // Historical source indirection must not leak into the unpublished build.
                        try(SqliteStore store=new SqliteStore(db);Statement statement=store.connection().createStatement()) {
                            statement.executeUpdate("DELETE FROM project_meta WHERE key LIKE 'snapshot_%'");
                        }
                    }
                    long copyMs=(System.nanoTime()-copyStarted)/1_000_000;
                    metric("backup_ms",copyMs);if(baseline!=null) metric("backups",1);
                    long indexStarted=System.nanoTime();
                    Map<String,Object> metrics;
                    try(var processes=SnapshotProcesses.enter(directory)) {
                        metrics=new LinkedHashMap<>(builder.build(project,db,baseline!=null));
                    }
                    metric("index_ms",(System.nanoTime()-indexStarted)/1_000_000);
                    if(metrics.get("reparsed_files") instanceof Number count) metric("reparsed_files",count.longValue());
                    metrics.put("materialize_ms",materializeMs);
                    metrics.put("capture_policy",SnapshotCapture.POLICY);
                    metrics.put("baseline",baseline==null?"":baseline.id()); metrics.put("backup_ms",copyMs);
                    Set<Path> frozenInputs=new HashSet<>();
                    try(SqliteStore store=new SqliteStore(db);Statement statement=store.connection().createStatement();
                        ResultSet rows=statement.executeQuery("SELECT source_file FROM file_cache")) {
                        while(rows.next()) frozenInputs.add(project.resolve(rows.getString(1)));
                    }
                    Map<String,String> after=inventory(project,frozenInputs,builder.artifacts(project));
                    Set<String> retained=new HashSet<>(before.keySet());
                    frozenInputs.forEach(path->retained.add(SnapshotFiles.relative(project,path)));
                    after.keySet().retainAll(retained);
                    // Build tools may add generated inputs. Original source/configuration bytes must remain stable.
                    for(var file:before.entrySet()) if(!Objects.equals(file.getValue(),after.get(file.getKey())))
                        throw new SnapshotException("SNAPSHOT_INPUT_CHANGED","Build modified input: " + file.getKey());
                    verifyIndexedInputs(db,after);
                    Path blobs=directory.resolve("blobs");
                    long sourceStarted=System.nanoTime();
                    SourceBlobStore.Stats cached=new SourceBlobStore(blobs).store(project,after);
                    metric("source_store_ms",(System.nanoTime()-sourceStarted)/1_000_000);metric("source_written_bytes",cached.writtenBytes());
                    metrics.put("source_written_bytes",cached.writtenBytes());
                    metrics.put("source_written_files",cached.writtenFiles());metrics.put("source_reused_files",cached.reusedFiles());
                    Files.writeString(owned.resolve("files.json"),Json.writeCompact(after));
                    String profile,source;
                    try(SqliteStore store=new SqliteStore(db)) {
                        store.upsertProjectMeta(Map.of("snapshot_blob_root",blobs.toString(),"snapshot_id",id,
                                "snapshot_capture_policy",SnapshotCapture.POLICY,
                                "snapshot_kind",working?"WORKTREE":"COMMIT","source_git_commit",sha,
                                "source_git_branch",working?"WORKTREE":ref,"source_git_dirty",String.valueOf(working)));
                        SemanticIdentity identity=SemanticIdentity.read(store);
                        profile=identity.semanticProfileId(); source=identity.sourceSnapshotId();
                        String entries=store.readProjectMeta("classpath_entries").orElse("");
                        Map<String,String> captured=capturedArtifacts(entries,project.toRealPath());
                        store.upsertProjectMeta("snapshot_captured_artifacts",Json.writeCompact(captured));
                        store.upsertProjectMeta("snapshot_artifacts",artifactFingerprint(entries,captured));
                        try(Statement statement=store.connection().createStatement()) { statement.execute("PRAGMA wal_checkpoint(TRUNCATE)"); }
                    }
                    metrics.put("total_ms",(System.nanoTime()-started)/1_000_000);
                    Files.writeString(db.resolveSibling("index.db.snapshot"),id);
                    catalog.publish(id,profile,source,Json.writeCompact(metrics),selectorKey(ref),sha);
                    published=true;
                    return new Built(catalog.get(id),db,false);
                } catch(Exception failure) {
                    primary=failure;
                    try {
                        catalog.state(id,"FAILED");
                        SnapshotRecovery.deleteContents(this,id);
                    } catch(Exception cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                } finally {
                    long cleanupStarted=System.nanoTime();
                    if(materialized) try { git.removeMaterialization(workspace); }
                    catch(Exception cleanup) {
                        if(primary!=null) primary.addSuppressed(cleanup);
                        System.err.println(Json.writeCompact(Map.of("record","warning","code","SNAPSHOT_CLEANUP_PENDING",
                                "snapshot_id",id,"published",published,"message",cleanup.getMessage())));
                    }
                    metric("cleanup_ms",(System.nanoTime()-cleanupStarted)/1_000_000);
                }
            }
        } catch(SnapshotException failure) { throw failure; }
        catch(Exception failure) { throw new SnapshotException("SNAPSHOT_BUILD_FAILED",failure.getMessage(),failure); }
    }
    private void captureWorkingTree(Path workspace,Map<String,String> expected,Builder builder) throws Exception {
        for(int attempt=0;attempt<2;attempt++) {
            try {
                SnapshotFiles.copy(git.root(),workspace,expected);
                if(expected.equals(captureInventory(git.root(),git.project(),builder))) return;
            } catch(SnapshotException changed) {
                if(!changed.code().equals("WORKTREE_CHANGED")) throw changed;
            }
        }
        throw new SnapshotException("WORKTREE_CHANGED","Working tree changed during capture; retry indexing");
    }
    private static void verifyIndexedInputs(Path db,Map<String,String> frozen) throws SQLException {
        try(SqliteStore store=new SqliteStore(db);Statement statement=store.connection().createStatement();
            ResultSet files=statement.executeQuery("SELECT source_file,hash FROM file_cache")) {
            while(files.next()) {
                String file=files.getString("source_file"),hash=files.getString("hash");
                if(!frozen.containsKey(file)) throw new SnapshotException("SNAPSHOT_SOURCE_OUTSIDE_PROJECT",
                        "Indexed source is outside the captured project; index a common project root: " + file);
                if(!Objects.equals(hash,frozen.get(file))) throw new SnapshotException("SNAPSHOT_INPUT_CHANGED",
                        "Indexed source differs from frozen content: " + file);
            }
        }
    }
    private String requestHash(String request) throws Exception {
        Path userConfig=Path.of(System.getProperty("user.home"),".anatomist","config.toml");
        Properties build=new Properties();
        try(var resource=SnapshotService.class.getResourceAsStream("/anatomist-version.properties")) { if(resource!=null) build.load(resource); }
        return FileCacheService.sha256OfString(SnapshotCapture.POLICY + "\n" + request.replace(git.project().toString(),"$PROJECT")
                + "\n" + System.getProperty("java.version") + "\n" + System.getProperty("java.home")
                + "\n" + build.getProperty("version","") + "\n" + Objects.toString(System.getenv("ANATOMIST_JDK_HOME"),"")
                + "\n" + com.anatomist.framework.spring.BuiltInExtensions.currentFingerprint()
                + "\n" + IndexSchema.VERSION + "\n" + com.anatomist.core.GraphSemantics.VERSION
                + "\n" + (Files.isRegularFile(userConfig)?FileCacheService.sha256(userConfig):""));
    }
    private SnapshotCatalog.Entry baseline(SnapshotCatalog catalog,String sha,boolean working,String requestHash) {
        if(working) for(var entry:catalog.forCheckout(git.checkoutKey(),requestHash))
            if(artifactsCurrent(database(entry.id()))) return entry;
        for(String ancestor:git.ancestors(sha)) for(var entry:catalog.candidates(ancestor,requestHash))
            if(entry.status().equals("READY") && entry.checkout().isEmpty() && artifactsCurrent(database(entry.id()))) return entry;
        return null;
    }
    private Map<String,String> artifactInventory(Builder builder) throws Exception {
        Map<String,String> result=new TreeMap<>();
        for(Path artifact:builder.artifacts(git.project())) {
            if(!artifact.toAbsolutePath().normalize().startsWith(git.project())) continue;
            if(Files.isDirectory(artifact)) {
                for(var file:SnapshotFiles.inventory(artifact).entrySet()) result.put(SnapshotFiles.relative(git.root(),artifact.resolve(file.getKey())),file.getValue());
            } else if(Files.isRegularFile(artifact)) result.put(SnapshotFiles.relative(git.root(),artifact),FileCacheService.sha256(artifact));
        }
        return result;
    }
    private Map<String,String> inventory(Path project,Collection<Path> inputs,Collection<Path> artifacts) throws Exception {
        long started=System.nanoTime();Map<String,String> files=SnapshotCapture.inventory(project,inputs,artifacts);
        long bytes=0;for(String path:files.keySet()) bytes+=Files.size(project.resolve(path));
        metric("captured_hash_bytes",bytes);metric("captured_hash_files",files.size());metric("capture_ms",(System.nanoTime()-started)/1_000_000);
        return files;
    }
    private Map<String,String> captureInventory(Path root,Path project,Builder builder) throws Exception {
        // Keep repository-level build files available, but hash only the selected project's inputs.
        Map<String,String> projectFiles=inventory(project,builder.inputs(project),builder.artifacts(project));
        Map<String,String> result=new TreeMap<>();
        projectFiles.forEach((path,hash)->result.put(SnapshotFiles.relative(root,project.resolve(path)),hash));
        if(!root.equals(project)) {
            for(Path parent=project.getParent();parent!=null && parent.startsWith(root);parent=parent.getParent())
                for(String name:List.of("pom.xml","settings.gradle","settings.gradle.kts","build.gradle","build.gradle.kts")) {
                    Path file=parent.resolve(name);
                    if(Files.isRegularFile(file)) result.put(SnapshotFiles.relative(root,file),FileCacheService.sha256(file));
                }
        }
        return result;
    }
    private boolean artifactsCurrent(Path db) {
        if(!Files.isRegularFile(db)) return false;
        try(IndexLock lease=IndexLock.forRead(db)) {
            if(!Files.isRegularFile(db) || !Files.isRegularFile(db.resolveSibling("files.json"))) return false;
            metric("snapshot_validations",1);
            Map<String,String> meta=new HashMap<>();
            try(Connection connection=DriverManager.getConnection("jdbc:sqlite:"+db.toUri()+"?mode=ro");
                Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT key,value FROM project_meta WHERE key IN ('classpath_entries','snapshot_captured_artifacts','snapshot_artifacts')")) {
                while(rows.next()) meta.put(rows.getString(1),rows.getString(2));
            }
            String entries=meta.getOrDefault("classpath_entries","");Map<String,String> captured=new HashMap<>();
            if(Json.parseTree(meta.getOrDefault("snapshot_captured_artifacts","{}")) instanceof Map<?,?> values)
                values.forEach((key,value)->captured.put(key.toString(),value.toString()));
            StringBuilder fingerprint=new StringBuilder();
            for(String value:entries.split(java.io.File.pathSeparator)) {
                if(value.isBlank()) continue;
                String hash=captured.get(value);
                if(hash==null) {
                    Path path=Path.of(value).toAbsolutePath().normalize();hash=validatedArtifacts.get(path);
                    if(hash==null) { hash=artifactFingerprint(path);validatedArtifacts.put(path,hash);metric("external_artifact_hashes",1); }
                }
                fingerprint.append(value).append('\0').append(hash).append('\n');
            }
            return FileCacheService.sha256OfString(fingerprint.toString()).equals(meta.getOrDefault("snapshot_artifacts","unrecorded"));
        } catch(Exception failure) { return false; }
    }
    /** Private project outputs are frozen inputs, not live external dependencies. */
    private static Map<String,String> capturedArtifacts(String entries,Path project) throws Exception {
        Map<String,String> out=new TreeMap<>();
        for(String value:entries.split(java.io.File.pathSeparator)) {
            if(!value.isBlank() && Path.of(value).toAbsolutePath().normalize().startsWith(project))
                out.put(value,artifactFingerprint(Path.of(value)));
        }
        return out;
    }
    private static String artifactFingerprint(Path entry) throws Exception {
        if(Files.isRegularFile(entry)) return FileCacheService.sha256(entry);
        if(Files.isDirectory(entry)) return SnapshotFiles.fingerprint(SnapshotFiles.inventory(entry));
        return "missing";
    }
    private static String artifactFingerprint(String entries,Map<String,String> captured) throws Exception {
        StringBuilder out=new StringBuilder();
        for(String path:entries.split(java.io.File.pathSeparator)) {
            if(path.isBlank()) continue;
            Path entry=Path.of(path); out.append(path).append('\0');
            out.append(captured.containsKey(path)?captured.get(path):artifactFingerprint(entry));
            out.append('\n');
        }
        return FileCacheService.sha256OfString(out.toString());
    }
}
