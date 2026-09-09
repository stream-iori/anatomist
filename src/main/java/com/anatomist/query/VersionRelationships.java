package com.anatomist.query;

import com.anatomist.query.semantic.RelationshipIdentity;
import java.sql.*;
import java.util.*;

/** Location-independent multiset of persisted relationships, with bounded call impact. */
final class VersionRelationships {
    record Relation(Map<String,Object> fields,int count,List<Map<String,Object>> sites,Map<String,Object> dispatch) {
        Relation(Map<String,Object> fields,int count,List<Map<String,Object>> sites) { this(fields,count,sites,Map.of("candidate_kind","resolved")); }
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
    static List<Relation> incoming(Connection c,String target) throws SQLException {
        Map<String,Relation> out=new TreeMap<>();
        read(c,"SELECT e.source_id source,coalesce(e.target_id,e.external_target_fqn) target,e.relation,"
                + "e.semantic,e.mechanism,e.call_kind,e.resolution,e.confidence,e.provider_id,e.producer_id,"
                + "e.source_file file,e.begin_line line,e.begin_column col FROM edges e JOIN nodes n ON n.id=e.source_id "
                + "WHERE (?='ALL' OR n.scope=?) AND (? IS NULL OR n.module=?) AND e.relation='CALLS' AND e.target_id=?","ALL",null,out,target);
        read(c,"SELECT o.caller_id source,coalesce(t.target_id,t.external_target_fqn) target,'CALLS' relation,"
                + "cs.receiver_static_type semantic,cs.syntax_target mechanism,cs.dispatch_kind call_kind,"
                + "t.resolution_status resolution,t.confidence,cs.provider_id,cs.producer_id,"
                + "o.source_file file,cs.begin_line line,cs.begin_column col FROM call_sites cs "
                + "JOIN call_site_owners o ON o.owner_pk=cs.owner_pk JOIN call_site_targets t ON t.call_site_pk=cs.site_pk "
                + "JOIN nodes n ON n.id=o.caller_id WHERE (?='ALL' OR n.scope=?) AND (? IS NULL OR n.module=?) AND t.target_id=?","ALL",null,out,target);
        return List.copyOf(out.values());
    }
    private static void read(Connection c,String sql,String scope,String module,Map<String,Relation> out) throws SQLException {
        read(c,sql,scope,module,out,null);
    }
    private static void read(Connection c,String sql,String scope,String module,Map<String,Relation> out,String target) throws SQLException {
        try(PreparedStatement s=c.prepareStatement(sql)) {
            s.setString(1,scope);s.setString(2,scope);s.setString(3,module);s.setString(4,module);
            if(target!=null) s.setString(5,target);
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
        return impacts(side,snapshot,id->incoming.getOrDefault(id,List.of()),seeds,declarations,scope,module,depth,out,entityLimit,stateLimit);
    }
    static Set<String> impacts(String side,String snapshot,java.util.function.Function<String,List<Relation>> incoming,Set<String> seeds,
                               Map<String,DiffNavigation.Declaration> declarations,String scope,String module,int depth,
                               List<Map<String,Object>> out,int entityLimit,int stateLimit) {
        record Visit(String node,List<String> path,List<Map<String,Object>> edges,boolean possible,String order) {}
        Comparator<Visit> order=Comparator.comparingInt((Visit v)->v.path().size()).thenComparing(Visit::possible).thenComparing(Visit::order);
        Set<String> reasons=new TreeSet<>(), entities=new HashSet<>();int states=0;
        for(String seed:new TreeSet<>(seeds)) {
            var origin=declarations.get(seed);if(origin==null || !origin.callable()) continue;
            PriorityQueue<Visit> queue=new PriorityQueue<>(order);Map<String,Visit> best=new HashMap<>();Set<String> visited=new HashSet<>();
            Visit start=new Visit(seed,List.of(seed),List.of(),false,seed);queue.add(start);best.put(seed,start);
            while(!queue.isEmpty()) {
                Visit current=queue.remove();
                if(best.get(current.node())!=current || visited.contains(current.node())) continue;
                if(!entities.contains(current.node()) && entities.size()>=entityLimit) { reasons.add("ENTITY_LIMIT");return reasons; }
                if(states>=stateLimit) { reasons.add("STATE_LIMIT");return reasons; }
                entities.add(current.node());visited.add(current.node());states++;
                var caller=declarations.get(current.node());
                if(!current.node().equals(seed) && caller!=null && caller.selected(scope,module)) {
                    Map<String,Object> row=new LinkedHashMap<>();row.put("record","impact");row.put("side",side);row.put("entity",current.node());
                    row.put("origin",origin.anchor(snapshot,origin.located()?"declaration":"unlocated"));
                    row.put("caller",caller.anchor(snapshot,caller.located()?"declaration":"unlocated"));
                    row.put("path",current.path());row.put("edges",current.edges());row.put("contains_possible_dispatch",current.possible());
                    row.put("path_interpretation","one_shortest_path_per_origin");row.put("interpretation","static_possible_impact");out.add(row);
                }
                List<Relation> callers=incoming.apply(current.node());
                if(current.path().size()-1>=depth) {
                    if(callers.stream().anyMatch(r->!visited.contains(r.source()) && !best.containsKey(r.source()))) reasons.add("DEPTH_LIMIT");
                    continue;
                }
                for(Relation relation:callers) {
                    if(visited.contains(relation.source())) continue;
                    List<String> path=new ArrayList<>();path.add(relation.source());path.addAll(current.path());
                    Map<String,Object> edge=new LinkedHashMap<>(relation.dispatch());
                    edge.put("source",relation.source());edge.put("target",relation.target());edge.put("sites",relation.sites());edge.put("sites_interpretation","representative_samples");
                    List<Map<String,Object>> edges=new ArrayList<>();edges.add(edge);edges.addAll(current.edges());
                    boolean possible=current.possible() || "possible".equals(relation.dispatch().get("candidate_kind"));
                    String key=String.join("\n",path)+"\n"+com.anatomist.json.Json.writeCompact(canonical(edges));
                    Visit next=new Visit(relation.source(),List.copyOf(path),List.copyOf(edges),possible,key);
                    Visit previous=best.get(next.node());
                    if(previous==null || order.compare(next,previous)<0) { best.put(next.node(),next);queue.add(next); }
                }
            }
        }
        return reasons;
    }

    private static Object canonical(Object value) {
        if(value instanceof Map<?,?> map) {
            Map<String,Object> sorted=new TreeMap<>();map.forEach((k,v)->sorted.put(k.toString(),canonical(v)));return sorted;
        }
        if(value instanceof List<?> list) return list.stream().map(VersionRelationships::canonical).toList();
        return value;
    }
}
