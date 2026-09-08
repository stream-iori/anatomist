package com.anatomist.query;

import com.anatomist.json.Json;
import com.anatomist.version.*;
import com.anatomist.store.FileCacheService;
import com.anatomist.store.SnapshotCatalog;
import com.anatomist.store.SourceBlobStore;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Frozen text changes lead to navigation; indexed relationships remain separate evidence. */
public final class VersionDiffService {
    public static final String CONTRACT="anatomist-diff/v2";
    public record Result(Map<String,Object> header,List<Map<String,Object>> changes,Map<String,Object> evidence) {
        public Map<String,Object> json() { return Map.of("contract",CONTRACT,"comparison",header,"changes",changes,"evidence",evidence); }
    }
    public Result compare(SnapshotAccess service,SnapshotCatalog.Entry base,SnapshotCatalog.Entry target,
                          String scope,String module,boolean impact,int depth) {
        return compare(service,base,target,scope,module,impact,depth,scope,null);
    }
    public Result compare(SnapshotAccess service,SnapshotCatalog.Entry base,SnapshotCatalog.Entry target,
                          String scope,String module,boolean impact,int depth,String impactScope,String impactModule) {
        try(QueryService left=new QueryService(service.database(base.id()));
            QueryService right=new QueryService(service.database(target.id()))) {
            Map<String,String> oldMeta=metadata(left.connection()),newMeta=metadata(right.connection());
            Map<String,String> oldFiles=files(service,base.id()),newFiles=files(service,target.id());
            var oldCoverage=new DiffCoverage(left.connection(),oldMeta,oldFiles.keySet());
            var newCoverage=new DiffCoverage(right.connection(),newMeta,newFiles.keySet());
            validateModule(module,oldCoverage,newCoverage);
            if(impact) validateModule(impactModule,oldCoverage,newCoverage);
            var environmentDifferences=environment(oldMeta,newMeta);
            boolean environmentChanged=!base.profile().equals(target.profile()) || !environmentDifferences.isEmpty();
            Map<String,Object> header=new LinkedHashMap<>();
            header.put("contract",CONTRACT); header.put("record","diff_header");
            header.put("base",identity(base,left.connection())); header.put("target",identity(target,right.connection()));
            header.put("environment_changed",environmentChanged); header.put("environment_differences",environmentDifferences);
            header.put("selection",Map.of("files","project_manifest","scope",scope,"module",module==null?"*":module));
            header.put("navigation_interpretation","text_touched_not_behavior_change");
            header.put("impact",Map.of("requested",impact,"model","reverse_static_calls","scope",impactScope,
                    "module",impactModule==null?"*":impactModule,"depth",depth,"entity_limit",10_000,"state_limit",100_000,
                    "selection_applies_to","returned_callers","traversal","captured_call_graph"));

            List<Map<String,Object>> changes=new ArrayList<>();
            fileChanges(service,base,target,oldFiles,newFiles,changes);
            var before=DiffNavigation.read(left.connection()); var after=DiffNavigation.read(right.connection());
            Map<String,String> oldHits=new TreeMap<>(),newHits=new TreeMap<>();
            Set<String> oldGaps=oldCoverage.reasons(scope,module,"declarations");
            Set<String> newGaps=newCoverage.reasons(scope,module,"declarations");
            Set<String> changedFiles=new TreeSet<>(oldFiles.keySet()); changedFiles.addAll(newFiles.keySet());
            changedFiles.removeIf(file->Objects.equals(oldFiles.get(file),newFiles.get(file)));
            var oldSelected=before.values().stream().filter(d->d.selected(scope,module)).toList();
            var newSelected=after.values().stream().filter(d->d.selected(scope,module)).toList();
            for(String file:changedFiles) {
                if(!file.endsWith(".java")) continue;
                Path oldSource=verified(oldMeta,file,oldFiles.get(file),oldGaps);
                Path newSource=verified(newMeta,file,newFiles.get(file),newGaps);
                if((oldFiles.containsKey(file) && oldSource==null) || (newFiles.containsKey(file) && newSource==null)) continue;
                if(oldSource==null || newSource==null) {
                    markFile(oldSelected,file,oldHits); markFile(newSelected,file,newHits);
                } else {
                    var hunks=DiffTextChanges.compare(oldSource,newSource);
                    oldHits.putAll(DiffNavigation.locate(oldSelected,file,hunks.stream().map(DiffTextChanges.Hunk::before).toList()));
                    newHits.putAll(DiffNavigation.locate(newSelected,file,hunks.stream().map(DiffTextChanges.Hunk::after).toList()));
                    oldSelected.stream().filter(d->d.file().equals(file) && !after.containsKey(d.id()))
                            .forEach(d->oldHits.put(d.id(),precision(d)));
                    newSelected.stream().filter(d->d.file().equals(file) && !before.containsKey(d.id()))
                            .forEach(d->newHits.put(d.id(),precision(d)));
                }
                missingRanges(oldSelected,file,oldHits,oldGaps);
                missingRanges(newSelected,file,newHits,newGaps);
            }
            Set<String> changed=new TreeSet<>(oldHits.keySet());changed.addAll(newHits.keySet());
            for(String id:changed) {
                var a=before.get(id);var b=after.get(id);
                Map<String,Object> change=new LinkedHashMap<>();
                change.put("record","declaration_change"); change.put("change",a==null?"added":b==null?"deleted":"modified");
                change.put("entity",id);
                if(a!=null) change.put("before",a.anchor(base.id(),oldHits.getOrDefault(id,precision(a))));
                if(b!=null) change.put("after",b.anchor(target.id(),newHits.getOrDefault(id,precision(b))));
                changes.add(change);
            }
            var oldRelations=VersionRelationships.read(left.connection(),scope,module);
            var newRelations=VersionRelationships.read(right.connection(),scope,module);
            VersionRelationships.changes(oldRelations,newRelations,changes,changed);
            for(var change:changes) if(change.get("record").equals("file_change")) {
                String file=change.get("path").toString(), old=change.getOrDefault("before_path",file).toString();
                if(oldFiles.containsKey(old)) change.put("before",Map.of("snapshot_id",base.id(),"file",old,"precision","file"));
                if(newFiles.containsKey(file)) change.put("after",Map.of("snapshot_id",target.id(),"file",file,"precision","file"));
            }

            Map<String,Object> capabilities=new LinkedHashMap<>();
            capabilities.put("files",DiffCoverage.evidence(Set.of(),false,false));
            capabilities.put("declarations",paired(oldGaps,newGaps,false,false));
            capabilities.put("relations",paired(oldCoverage.reasons(scope,module,"relations"),
                    newCoverage.reasons(scope,module,"relations"),environmentChanged,false));
            if(impact) {
                var oldCalls=VersionRelationships.read(left.connection(),"ALL",null);
                var newCalls=VersionRelationships.read(right.connection(),"ALL",null);
                var oldLimits=VersionRelationships.impacts("base",base.id(),oldCalls,changed,before,impactScope,impactModule,depth,changes);
                var newLimits=VersionRelationships.impacts("target",target.id(),newCalls,changed,after,impactScope,impactModule,depth,changes);
                var oldImpact=oldCoverage.reasons("ALL",null,"impact"); var newImpact=newCoverage.reasons("ALL",null,"impact");
                oldImpact.addAll(oldCoverage.reasons(impactScope,impactModule,"impact"));
                newImpact.addAll(newCoverage.reasons(impactScope,impactModule,"impact"));
                oldImpact.addAll(oldGaps);newImpact.addAll(newGaps);
                oldImpact.addAll(oldLimits);newImpact.addAll(newLimits);
                Map<String,Object> impactEvidence=paired(oldImpact,newImpact,environmentChanged,!oldLimits.isEmpty()||!newLimits.isEmpty());
                impactEvidence.put("model","reverse_static_calls");impactEvidence.put("paths","one_shortest_path_per_origin");
                impactEvidence.put("entity_coverage",impactEvidence.get("status"));impactEvidence.put("origin_coverage",impactEvidence.get("status"));
                impactEvidence.put("unsupported_seed_kinds",List.of("type","field"));
                capabilities.put("impact",impactEvidence);
            } else capabilities.put("impact",Map.of("status","not_requested","model","reverse_static_calls"));
            Map<String,Object> evidence=new LinkedHashMap<>();
            evidence.put("record","evidence");evidence.put("scope","comparison");
            evidence.put("capabilities",capabilities);evidence.put("emitted",changes.size());
            return new Result(header,changes,evidence);
        } catch(Exception failure) {
            if(failure instanceof SnapshotException snapshot) throw snapshot;
            if(failure instanceof IllegalArgumentException argument) throw argument;
            throw new SnapshotException("DIFF_FAILED",failure.getMessage(),failure);
        }
    }

    private static void validateModule(String module,DiffCoverage a,DiffCoverage b) {
        if(module!=null && a.known() && b.known() && !a.hasModule(module) && !b.hasModule(module))
            throw new IllegalArgumentException("Unknown module in both snapshots: "+module);
    }
    private static String precision(DiffNavigation.Declaration d) { return d.located()?"declaration":"unlocated"; }
    private static void markFile(List<DiffNavigation.Declaration> declarations,String file,Map<String,String> hits) {
        declarations.stream().filter(d->d.file().equals(file)).forEach(d->hits.put(d.id(),precision(d)));
    }
    private static void missingRanges(List<DiffNavigation.Declaration> declarations,String file,Map<String,String> hits,Set<String> gaps) {
        var local=declarations.stream().filter(d->d.file().equals(file)).toList();
        boolean fallback=local.stream().noneMatch(d->hits.containsKey(d.id()))
                || local.stream().anyMatch(d->hits.containsKey(d.id()) && (d.type() || !d.located()));
        if(fallback && local.stream().anyMatch(d->!d.synthetic() && !d.located())) gaps.add("DECLARATION_RANGE_UNAVAILABLE");
    }
    private static Map<String,Object> paired(Set<String> a,Set<String> b,boolean environment,boolean truncated) {
        Set<String> reasons=new TreeSet<>(a);reasons.addAll(b);
        var out=DiffCoverage.evidence(reasons,environment,truncated);
        out.put("base_reasons",List.copyOf(new TreeSet<>(a)));out.put("target_reasons",List.copyOf(new TreeSet<>(b)));
        return out;
    }
    private static Map<String,Object> identity(SnapshotCatalog.Entry entry,Connection c) {
        Map<String,Object> out=new LinkedHashMap<>();
        for(String key:List.of("id","commit","source_snapshot_id","semantic_profile_id")) out.put(key,entry.json().get(key));
        out.put("index_revision_id",com.anatomist.query.semantic.SemanticIdentity.read(c).indexRevisionId());return out;
    }
    private static Path verified(Map<String,String> meta,String file,String hash,Set<String> gaps) {
        if(hash==null) return null;
        try {
            Path path=meta.containsKey("snapshot_blob_root")?new SourceBlobStore(Path.of(meta.get("snapshot_blob_root"))).path(hash)
                    :meta.containsKey("snapshot_sources")?SnapshotFiles.resolve(Path.of(meta.get("snapshot_sources")),file):null;
            if(path==null || !Files.isRegularFile(path)) { gaps.add("SNAPSHOT_SOURCE_MISSING");return null; }
            if(!hash.equals(FileCacheService.sha256(path))) { gaps.add("SNAPSHOT_SOURCE_CORRUPT");return null; }
            return path;
        } catch(RuntimeException failure) { gaps.add("SNAPSHOT_SOURCE_UNREADABLE");return null; }
    }
    private static Map<String,Object> environment(Map<String,String> before,Map<String,String> after) {
        Map<String,Object> changes=new TreeMap<>();
        for(String key:List.of("java_version","classpath_mode","snapshot_artifacts","spring_xml",
                "semantic_profile_inputs","extension_fingerprint","scan_policy_hash")) {
            if(!Objects.equals(before.get(key),after.get(key))) changes.put(key,Map.of(
                    "before",before.getOrDefault(key,""),"after",after.getOrDefault(key,"")));
        } return changes;
    }
    private static Map<String,String> metadata(Connection c) throws SQLException {
        Map<String,String> out=new HashMap<>();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT key,value FROM project_meta")) {
            while(r.next()) out.put(r.getString(1),r.getString(2));
        } return out;
    }
    @SuppressWarnings("unchecked")
    private static Map<String,String> files(SnapshotAccess service,String id) throws Exception {
        return (Map<String,String>)Json.parseTree(Files.readString(service.snapshotDirectory(id).resolve("files.json")));
    }
    private static void fileChanges(SnapshotAccess service,SnapshotCatalog.Entry a,SnapshotCatalog.Entry b,
                                    Map<String,String> before,Map<String,String> after,List<Map<String,Object>> changes) {
        Set<String> paths=new TreeSet<>(before.keySet()); paths.addAll(after.keySet());
        if(a.checkout().isEmpty() && b.checkout().isEmpty()) {
            String raw=new String(GitRepository.bytes(service.git().root(),"diff","--name-status","-z","-M",
                    a.commit(),b.commit(),"--",service.git().projectRelative().toString().isEmpty()?".":service.git().projectRelative().toString()),
                    java.nio.charset.StandardCharsets.UTF_8);
            String[] records=raw.split("\0");
            for(int i=0;i<records.length;) {
                String status=records[i++]; if(i>=records.length) break;
                String old=records[i++];
                if(status.startsWith("R") && i<records.length) {
                    String now=records[i++];String prefix=service.git().projectRelative().toString().replace('\\','/');
                    if(!prefix.isEmpty()) {
                        if(!old.startsWith(prefix+"/") || !now.startsWith(prefix+"/")) continue;
                        old=old.substring(prefix.length()+1); now=now.substring(prefix.length()+1);
                    }
                    if(before.containsKey(old) && after.containsKey(now)) {
                        paths.remove(old); paths.remove(now);
                        changes.add(new LinkedHashMap<>(Map.of("record","file_change","change","renamed","before_path",old,"path",now,
                                "similarity",Integer.parseInt(status.substring(1)),"origin","git")));
                    }
                }
            }
        }
        for(String path:paths) {
            String old=before.get(path),now=after.get(path); if(Objects.equals(old,now)) continue;
            Map<String,Object> change=new LinkedHashMap<>();change.put("record","file_change"); change.put("path",path);
            change.put("change",old==null?"added":now==null?"deleted":"modified");
            if(old!=null) change.put("before_hash",old); if(now!=null) change.put("after_hash",now);changes.add(change);
        }
    }
}
