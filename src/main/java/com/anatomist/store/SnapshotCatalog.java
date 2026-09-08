package com.anatomist.store;

import com.anatomist.version.SnapshotException;

import com.anatomist.json.Json;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Separate, versioned catalog; graph databases retain their existing schema. */
public final class SnapshotCatalog implements AutoCloseable {
    private final Connection connection;
    public SnapshotCatalog(Path directory) {
        try {
            Files.createDirectories(directory);
            connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("catalog.db"));
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA busy_timeout=60000");
                s.execute("PRAGMA foreign_keys=ON");
                try (ResultSet version = s.executeQuery("PRAGMA user_version")) {
                    if (version.getInt(1) > 1) throw new SnapshotException("CATALOG_INCOMPATIBLE", "Catalog is newer than this Anatomist");
                }
                s.execute("CREATE TABLE IF NOT EXISTS snapshots(id TEXT PRIMARY KEY,commit_sha TEXT NOT NULL,"
                        + "checkout TEXT NOT NULL,request_hash TEXT NOT NULL,input_hash TEXT NOT NULL,"
                        + "profile TEXT NOT NULL DEFAULT '',source_snapshot TEXT NOT NULL DEFAULT '',"
                        + "created TEXT NOT NULL,status TEXT NOT NULL,pinned INTEGER NOT NULL DEFAULT 0,"
                        + "metrics TEXT NOT NULL DEFAULT '{}')");
                s.execute("CREATE TABLE IF NOT EXISTS origins(selector TEXT NOT NULL,commit_sha TEXT NOT NULL,"
                        + "snapshot_id TEXT NOT NULL REFERENCES snapshots(id) ON DELETE CASCADE,"
                        + "PRIMARY KEY(selector,snapshot_id))");
                s.execute("CREATE TABLE IF NOT EXISTS heads(selector TEXT NOT NULL,request_hash TEXT NOT NULL,"
                        + "snapshot_id TEXT NOT NULL REFERENCES snapshots(id) ON DELETE CASCADE,"
                        + "PRIMARY KEY(selector,request_hash))");
                s.execute("PRAGMA user_version=1");
            }
        } catch (Exception failure) { throw failure("Cannot open snapshot catalog", failure); }
    }
    public record Entry(String id, String commit, String checkout, String requestHash, String inputHash,
                        String profile, String sourceSnapshot, String created, String status,
                        boolean pinned, String metrics) {
        public Map<String,Object> json() {
            Map<String,Object> out = new LinkedHashMap<>();
            out.put("id", id); out.put("commit", commit); out.put("checkout", checkout);
            out.put("semantic_profile_id", profile); out.put("source_snapshot_id", sourceSnapshot);
            out.put("created", created); out.put("status", status); out.put("pinned", pinned);
            out.put("metrics", Json.parseTree(metrics)); return out;
        }
    }
    public List<Entry> list() {
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery(
                "SELECT * FROM snapshots ORDER BY created DESC,id")) {
            List<Entry> out = new ArrayList<>();
            while (r.next()) out.add(new Entry(r.getString("id"),r.getString("commit_sha"),
                    r.getString("checkout"),r.getString("request_hash"),r.getString("input_hash"),
                    r.getString("profile"),r.getString("source_snapshot"),r.getString("created"),
                    r.getString("status"),r.getBoolean("pinned"),r.getString("metrics")));
            return out;
        } catch (SQLException failure) { throw failure("Cannot read snapshots", failure); }
    }
    public Entry get(String id) {
        return list().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow(() ->
                new SnapshotException("SNAPSHOT_MISSING", "Unknown snapshot: " + id));
    }
    public void begin(String id, String commit, String checkout, String request, String input) {
        update("INSERT INTO snapshots(id,commit_sha,checkout,request_hash,input_hash,created,status) VALUES(?,?,?,?,?,?,'BUILDING')",
                id, commit, checkout, request, input, Instant.now().toString());
    }
    public void publish(String id, String profile, String source, String metrics, String selector, String commit) {
        transaction(() -> {
            update("UPDATE snapshots SET status='READY',profile=?,source_snapshot=?,metrics=? WHERE id=?",
                    profile, source, metrics, id);
            origin(selector, commit, id);
        });
    }
    public void origin(String selector, String commit, String id) {
        transaction(() -> {
            update("INSERT OR IGNORE INTO origins VALUES(?,?,?)", selector,commit,id);
            if(!selector.matches("[a-fA-F0-9]{40,64}"))
                update("INSERT INTO heads SELECT ?,request_hash,id FROM snapshots WHERE id=? "
                        + "ON CONFLICT(selector,request_hash) DO UPDATE SET snapshot_id=excluded.snapshot_id", selector,id);
        });
    }
    public Set<String> idsForCommit(String commit) {
        return ids("SELECT o.snapshot_id FROM origins o JOIN snapshots s ON s.id=o.snapshot_id WHERE o.commit_sha=? AND s.checkout=''", commit);
    }
    public Set<String> heads(String selector) {
        return ids("SELECT snapshot_id FROM heads WHERE selector=?", selector);
    }
    public Set<String> protectedHeads() { return ids("SELECT snapshot_id FROM heads"); }
    public void state(String id, String state) { update("UPDATE snapshots SET status=? WHERE id=?", state,id); }
    public void pin(String id, boolean pin) { get(id); update("UPDATE snapshots SET pinned=? WHERE id=?", pin?"1":"0",id); }
    public void delete(String id) { update("DELETE FROM snapshots WHERE id=?", id); }
    private Set<String> ids(String sql, String... args) {
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            for(int i=0;i<args.length;i++) s.setString(i+1,args[i]);
            try(ResultSet r=s.executeQuery()) { Set<String> out=new HashSet<>(); while(r.next()) out.add(r.getString(1)); return out; }
        } catch(SQLException failure) { throw failure("Cannot read snapshot references",failure); }
    }
    private void update(String sql, String... args) {
        try(PreparedStatement s=connection.prepareStatement(sql)) {
            for(int i=0;i<args.length;i++) s.setString(i+1,args[i]); s.executeUpdate();
        } catch(SQLException failure) { throw failure("Cannot update snapshot catalog",failure); }
    }
    private void transaction(Runnable work) {
        try {
            if (!connection.getAutoCommit()) { work.run(); return; }
            connection.setAutoCommit(false);
            try { work.run(); connection.commit(); }
            catch (RuntimeException failure) { connection.rollback(); throw failure; }
            finally { connection.setAutoCommit(true); }
        } catch(SQLException failure) { throw failure("Catalog transaction failed",failure); }
    }
    @Override public void close() { try { connection.close(); } catch(SQLException failure) { throw failure("Cannot close catalog",failure); } }
    private static SnapshotException failure(String message, Exception cause) {
        return cause instanceof SnapshotException version ? version : new SnapshotException("CATALOG_FAILED", message,cause);
    }
}
