package com.anatomist.query;

import com.anatomist.query.semantic.RelationshipIdentity;
import java.sql.*;
import java.util.*;

/** Location-independent multiset of persisted relationships, with bounded call impact. */
final class VersionRelationships {
    record Relation(Map<String,Object> fields,int count,List<Map<String,Object>> sites) {
        String source() { return String.valueOf(fields.get("source")); }
        String target() { return String.valueOf(fields.get("target")); }
        String kind() { return String.valueOf(fields.get("relation")); }
    }
    static Map<String,Relation> read(Connection c,String scope,String module) throws SQLException {
        Map<String,Relation> out=new TreeMap<>();
        read(c,"SELECT e.source_id source,coalesce(e.target_id,e.external_target_fqn) target,e.relation,"
                + "e.semantic,e.mechanism,e.call_kind,e.resolution,e.confidence,e.provider_id,e.producer_id,"
                + "e.source_file file,e.begin_line line,e.begin_column col FROM edges e JOIN nodes n ON n.id=e.source_id "
                + "WHERE (?='ALL' OR n.scope=?) AND (? IS NULL OR n.module=?)",scope,module,out);
        read(c,"SELECT o.caller_id source,coalesce(t.target_id,t.external_target_fqn) target,'CALLS' relation,"
                + "cs.receiver_static_type semantic,cs.syntax_target mechanism,cs.dispatch_kind call_kind,"
                + "t.resolution_status resolution,t.confidence,cs.provider_id,cs.producer_id,"
                + "o.source_file file,cs.begin_line line,cs.begin_column col FROM call_sites cs "
                + "JOIN call_site_owners o ON o.owner_pk=cs.owner_pk JOIN call_site_targets t ON t.call_site_pk=cs.site_pk "
                + "JOIN nodes n ON n.id=o.caller_id WHERE (?='ALL' OR n.scope=?) AND (? IS NULL OR n.module=?)",scope,module,out);
        read(c,"SELECT a.node_id source,coalesce(a.annotation_fqn,a.raw_name) target,'ANNOTATION' relation,"
                + "a.attributes semantic,a.mechanism,a.target_kind call_kind,a.resolution_status resolution,"
                + "a.target_path confidence,a.provider_id,a.producer_id,a.source_file file,a.begin_line line,a.begin_column col "
                + "FROM annotations a JOIN nodes n ON n.id=a.node_id WHERE (?='ALL' OR n.scope=?) AND (? IS NULL OR n.module=?)",scope,module,out);
        return out;
    }
    private static void read(Connection c,String sql,String scope,String module,Map<String,Relation> out) throws SQLException {
        try(PreparedStatement s=c.prepareStatement(sql)) {
            s.setString(1,scope);s.setString(2,scope);s.setString(3,module);s.setString(4,module);
            try(ResultSet r=s.executeQuery()) { while(r.next()) {
                Map<String,Object> fields=new TreeMap<>();
                for(String field:List.of("source","target","relation","semantic","mechanism","call_kind","resolution","confidence","provider_id","producer_id"))
                    fields.put(field,Objects.toString(r.getString(field),""));
                String key=RelationshipIdentity.of("version-relation",fields);
                Relation prior=out.get(key); List<Map<String,Object>> sites=new ArrayList<>(prior==null?List.of():prior.sites());
                sites.add(Map.of("file",Objects.toString(r.getString("file"),""),"line",r.getInt("line"),"column",r.getInt("col")));
                sites.sort(Comparator.comparing((Map<String,Object> site)->site.get("file").toString())
                        .thenComparingInt(site->((Number)site.get("line")).intValue())
                        .thenComparingInt(site->((Number)site.get("column")).intValue()));
                if(sites.size()>3) sites.removeLast();
                out.put(key,new Relation(fields,prior==null?1:prior.count()+1,List.copyOf(sites)));
            }}
        }
    }
    static void changes(Map<String,Relation> before,Map<String,Relation> after,List<Map<String,Object>> out,Set<String> seeds) {
        Set<String> keys=new TreeSet<>(before.keySet());keys.addAll(after.keySet());
        for(String key:keys) {
            Relation a=before.get(key),b=after.get(key);
            if(a!=null && b!=null && a.count()==b.count()) continue;
            Relation relation=b==null?a:b;
            Map<String,Object> change=new LinkedHashMap<>();
            change.put("record","relation_change");change.put("id",key);
            change.put("change",a==null?"added":b==null?"deleted":"count_changed");
            change.put("relationship",relation.fields());change.put("before_count",a==null?0:a.count());change.put("after_count",b==null?0:b.count());
            change.put("before_sites",a==null?List.of():a.sites());change.put("after_sites",b==null?List.of():b.sites());
            change.put("sites_interpretation","representative_samples");
            out.add(change);seeds.add(relation.source());
        }
    }
    static Set<String> impacts(String side, String snapshot, Map<String,Relation> relations, Set<String> seeds,
                               Map<String,DiffNavigation.Declaration> declarations, String scope, String module,
                               int depth, List<Map<String,Object>> out) {
        return impacts(side,snapshot,relations,seeds,declarations,scope,module,depth,out,10_000,100_000);
    }
    static Set<String> impacts(String side, String snapshot, Map<String,Relation> relations, Set<String> seeds,
                               Map<String,DiffNavigation.Declaration> declarations, String scope, String module,
                               int depth, List<Map<String,Object>> out, int entityLimit, int stateLimit) {
        Map<String,List<Relation>> incoming=new HashMap<>();
        for(Relation r:relations.values()) if(r.kind().equals("CALLS")) incoming.computeIfAbsent(r.target(),k->new ArrayList<>()).add(r);
        incoming.values().forEach(list->list.sort(Comparator.comparing(Relation::source)
                .thenComparing(r->RelationshipIdentity.of("version-relation",r.fields()))));
        record Visit(String node,List<String> path,List<Map<String,Object>> edges) {}
        Set<String> reasons=new TreeSet<>(), entities=new HashSet<>(); int states=0;
        for(String seed:new TreeSet<>(seeds)) {
            var origin=declarations.get(seed);
            if(origin==null || !origin.callable()) continue;
            if(!entities.contains(seed) && entities.size()>=entityLimit) { reasons.add("ENTITY_LIMIT"); return reasons; }
            if(states>=stateLimit) { reasons.add("STATE_LIMIT"); return reasons; }
            entities.add(seed); states++;
            ArrayDeque<Visit> queue=new ArrayDeque<>(); Set<String> visited=new HashSet<>(); visited.add(seed);
            queue.add(new Visit(seed,List.of(seed),List.of()));
            while(!queue.isEmpty()) {
                Visit current=queue.removeFirst();
                List<Relation> callers=incoming.getOrDefault(current.node(),List.of());
                if(current.path().size()-1>=depth) {
                    if(callers.stream().anyMatch(r->!visited.contains(r.source()))) reasons.add("DEPTH_LIMIT");
                    continue;
                }
                for(Relation relation:callers) {
                    if(visited.contains(relation.source())) continue;
                    if(!entities.contains(relation.source()) && entities.size()>=entityLimit) { reasons.add("ENTITY_LIMIT"); return reasons; }
                    if(states>=stateLimit) { reasons.add("STATE_LIMIT"); return reasons; }
                    entities.add(relation.source()); visited.add(relation.source()); states++;
                    List<String> path=new ArrayList<>();path.add(relation.source());path.addAll(current.path());
                    List<Map<String,Object>> edges=new ArrayList<>();
                    edges.add(Map.of("source",relation.source(),"target",relation.target(),"sites",relation.sites(),
                            "sites_interpretation","representative_samples")); edges.addAll(current.edges());
                    var caller=declarations.get(relation.source());
                    if(caller!=null && caller.selected(scope,module)) {
                        Map<String,Object> row=new LinkedHashMap<>();
                        row.put("record","impact"); row.put("side",side); row.put("entity",relation.source());
                        row.put("origin",origin.anchor(snapshot,origin.located()?"declaration":"unlocated"));
                        row.put("caller",caller.anchor(snapshot,caller.located()?"declaration":"unlocated"));
                        row.put("path",List.copyOf(path)); row.put("edges",List.copyOf(edges));
                        row.put("path_interpretation","one_shortest_path_per_origin");
                        row.put("interpretation","static_possible_impact"); out.add(row);
                    }
                    queue.add(new Visit(relation.source(),List.copyOf(path),List.copyOf(edges)));
                }
            }
        } return reasons;
    }
}
