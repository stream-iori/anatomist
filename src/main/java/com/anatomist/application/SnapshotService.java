package com.anatomist.application;

import com.anatomist.cli.DefaultIndexPath;
import com.anatomist.json.Json;
import com.anatomist.query.semantic.SemanticIdentity;
import com.anatomist.store.*;
import com.anatomist.version.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Coordinates immutable version builds outside the user's checkout. */
public final class SnapshotService {
    public interface Builder {
        Map<String,Object> build(Path project, Path database, boolean incremental) throws Exception;
    }
    private final GitRepository git;
    private final Path directory;
    public SnapshotService(Path project) {
        git = GitRepository.open(project);
        directory = DefaultIndexPath.resolveHome(System.getenv(DefaultIndexPath.ENV_HOME),
                System.getProperty("user.home")).resolve("versions").resolve(git.key()).toAbsolutePath().normalize();
    }
    public GitRepository git() { return git; }
    public Path directory() { return directory; }
    public Path database(String id) { return snapshotDirectory(id).resolve("index.db"); }
    public Path snapshotDirectory(String id) {
        if (!id.matches("[a-f0-9]{32}")) throw new SnapshotException("SNAPSHOT_INVALID", "Invalid snapshot id: " + id);
        return directory.resolve("snapshots").resolve(id);
    }
    private String selectorKey(String ref) {
        return ref.equals("WORKTREE") ? "WORKTREE:"+git.checkoutKey() : ref;
    }
    public String commit(String ref) {
        if(ref.startsWith("snapshot:")) return entry(ref.substring(9)).commit();
        return git.commit(ref.equals("WORKTREE") ? "HEAD" : ref);
    }
    public SnapshotCatalog.Entry entry(String id) {
        requireCatalog();
        try(var catalog=new SnapshotCatalog(directory)) { return catalog.get(id); }
    }
    public SnapshotCatalog.Entry resolve(String ref) {
        requireCatalog();
        try(var catalog=new SnapshotCatalog(directory)) {
            if(ref.startsWith("snapshot:")) return requireReady(catalog.get(ref.substring(9)));
            Set<String> ids=ref.equals("WORKTREE") ? catalog.heads(selectorKey(ref)) : catalog.idsForCommit(commit(ref));
            List<SnapshotCatalog.Entry> entries=catalog.list().stream()
                    .filter(e->ids.contains(e.id()) && e.status().equals("READY")).toList();
            if(entries.isEmpty()) throw new SnapshotException("SNAPSHOT_MISSING",
                    "No snapshot for " + ref + "; run anatomist index " + git.project() + " --ref " + ref);
            if(entries.stream().map(SnapshotCatalog.Entry::profile).distinct().count()>1)
                throw new SnapshotException("SNAPSHOT_AMBIGUOUS","Multiple profiles for " + ref + "; select --snapshot <id>");
            return requireReady(entries.getFirst());
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
        if(ref.startsWith("snapshot:")) throw new IllegalArgumentException("Build requires a Git ref or WORKTREE");
        String sha=commit(ref);
        Path catalogPath=directory.resolve("catalog.db"), workspace=directory.resolve("workspace");
        try {
            Files.createDirectories(directory);
            if(directory.startsWith(git.root())) throw new SnapshotException("SNAPSHOT_STORAGE_INVALID",
                    "ANATOMIST_HOME must be outside the Git working tree for version indexing");
            try(var operation=IndexOperationLock.forWrite(catalogPath); var catalog=new SnapshotCatalog(directory)) {
                recover(catalog,workspace);
                boolean working=ref.equals("WORKTREE");
                String requestHash=requestHash(request);
                Map<String,String> workingFiles=null;
                if(working) { git.requireNoConflicts(); workingFiles=SnapshotFiles.inventory(git.root()); }
                String input=working ? SnapshotFiles.fingerprint(workingFiles) : sha;
                if(!full) for(var entry:catalog.list()) {
                    if(entry.status().equals("READY") && entry.inputHash().equals(input)
                            && entry.requestHash().equals(requestHash) && artifactsCurrent(database(entry.id()))) {
                        catalog.origin(selectorKey(ref),sha,entry.id());
                        return new Built(entry,database(entry.id()),true);
                    }
                }
                String id=UUID.randomUUID().toString().replace("-","");
                Path owned=snapshotDirectory(id),db=database(id);
                Files.createDirectories(owned);
                catalog.begin(id,sha,working?git.checkoutKey():"",requestHash,input);
                long started=System.nanoTime();
                boolean materialized=false;
                try {
                    git.materialize(sha,workspace); materialized=true;
                    if(working) {
                        // Preserve the private Git administrative file while freezing disk contents.
                        Map<String,String> committed=SnapshotFiles.inventory(workspace);
                        for(String path:committed.keySet()) if(!workingFiles.containsKey(path)) Files.delete(SnapshotFiles.resolve(workspace,path));
                        captureWorkingTree(workspace,workingFiles);
                        if(!sha.equals(git.commit("HEAD"))) throw new SnapshotException("WORKTREE_CHANGED","HEAD changed during capture");
                    }
                    Path project=workspace.resolve(git.projectRelative());
                    if(!Files.isDirectory(project)) throw new SnapshotException("PROJECT_MISSING_AT_REF","Project does not exist at " + ref);
                    Map<String,String> before=SnapshotFiles.inventory(project);
                    Map<String,Object> metrics=new LinkedHashMap<>(builder.build(project,db,false));
                    Map<String,String> after=SnapshotFiles.inventory(project);
                    // Build tools may add generated inputs. Original source/configuration bytes must remain stable.
                    for(var file:before.entrySet()) if(!Objects.equals(file.getValue(),after.get(file.getKey())))
                        throw new SnapshotException("SNAPSHOT_INPUT_CHANGED","Build modified input: " + file.getKey());
                    Path sources=owned.resolve("sources");
                    SnapshotFiles.copy(project,sources,after);
                    Files.writeString(owned.resolve("files.json"),Json.writeCompact(after));
                    String profile,source;
                    try(SqliteStore store=new SqliteStore(db)) {
                        store.upsertProjectMeta(Map.of("snapshot_sources",sources.toString(),"snapshot_id",id,
                                "snapshot_kind",working?"WORKTREE":"COMMIT","source_git_commit",sha,
                                "source_git_branch",working?"WORKTREE":ref,"source_git_dirty",String.valueOf(working)));
                        SemanticIdentity identity=SemanticIdentity.read(store);
                        profile=identity.semanticProfileId(); source=identity.sourceSnapshotId();
                        store.upsertProjectMeta("snapshot_artifacts",artifactFingerprint(store.readProjectMeta("classpath_entries").orElse("")));
                        try(Statement statement=store.connection().createStatement()) { statement.execute("PRAGMA wal_checkpoint(TRUNCATE)"); }
                    }
                    metrics.put("total_ms",(System.nanoTime()-started)/1_000_000);
                    Files.writeString(db.resolveSibling("index.db.snapshot"),id);
                    catalog.publish(id,profile,source,Json.writeCompact(metrics),selectorKey(ref),sha);
                    return new Built(catalog.get(id),db,false);
                } catch(Exception failure) {
                    catalog.state(id,"FAILED");
                    SnapshotFiles.deleteOwned(directory,owned);
                    throw failure;
                } finally {
                    if(materialized) git.removeMaterialization(workspace);
                }
            }
        } catch(SnapshotException failure) { throw failure; }
        catch(Exception failure) { throw new SnapshotException("SNAPSHOT_BUILD_FAILED",failure.getMessage(),failure); }
    }
    private void captureWorkingTree(Path workspace,Map<String,String> expected) throws Exception {
        for(int attempt=0;attempt<2;attempt++) {
            try {
                SnapshotFiles.copy(git.root(),workspace,expected);
                if(expected.equals(SnapshotFiles.inventory(git.root()))) return;
            } catch(SnapshotException changed) {
                if(!changed.code().equals("WORKTREE_CHANGED")) throw changed;
            }
        }
        throw new SnapshotException("WORKTREE_CHANGED","Working tree changed during capture; retry indexing");
    }
    private String requestHash(String request) throws Exception {
        Path userConfig=Path.of(System.getProperty("user.home"),".anatomist","config.toml");
        return FileCacheService.sha256OfString(request.replace(git.project().toString(),"$PROJECT")
                + "\n" + System.getProperty("java.version") + "\n" + System.getProperty("java.home")
                + "\n" + IndexSchema.VERSION + "\n" + com.anatomist.core.GraphSemantics.VERSION
                + "\n" + (Files.isRegularFile(userConfig)?FileCacheService.sha256(userConfig):""));
    }
    private void recover(SnapshotCatalog catalog,Path workspace) throws Exception {
        for(var entry:catalog.list()) if(entry.status().equals("BUILDING")) {
            catalog.state(entry.id(),"FAILED"); SnapshotFiles.deleteOwned(directory,snapshotDirectory(entry.id()));
        }
        if(Files.exists(workspace)) git.removeMaterialization(workspace);
    }
    private static boolean artifactsCurrent(Path db) {
        try(SqliteStore store=new SqliteStore(db)) {
            String entries=store.readProjectMeta("classpath_entries").orElse("");
            return artifactFingerprint(entries).equals(store.readProjectMeta("snapshot_artifacts").orElse("unrecorded"));
        } catch(Exception failure) { return false; }
    }
    private static String artifactFingerprint(String entries) throws Exception {
        StringBuilder out=new StringBuilder();
        for(String path:entries.split(java.io.File.pathSeparator)) {
            if(path.isBlank()) continue;
            Path entry=Path.of(path); out.append(path).append('\0');
            if(Files.isRegularFile(entry)) out.append(FileCacheService.sha256(entry));
            else if(Files.isDirectory(entry)) out.append(SnapshotFiles.fingerprint(SnapshotFiles.inventory(entry)));
            else out.append("missing");
            out.append('\n');
        }
        return FileCacheService.sha256OfString(out.toString());
    }
}
