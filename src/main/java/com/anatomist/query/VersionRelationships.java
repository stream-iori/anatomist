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
                if(sites.size()<3) sites.add(Map.of("file",Objects.toString(r.getString("file"),""),"line",r.getInt("line"),"column",r.getInt("col")));
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
            out.add(change);seeds.add(relation.source());
        }
    }
    static boolean impacts(String side,Map<String,Relation> relations,Set<String> seeds,int depth,List<Map<String,Object>> out) {
        Map<String,List<Relation>> incoming=new HashMap<>();
        for(Relation r:relations.values()) if(r.kind().equals("CALLS")) incoming.computeIfAbsent(r.target(),k->new ArrayList<>()).add(r);
        record Visit(String node,List<String> path) {}
        ArrayDeque<Visit> queue=new ArrayDeque<>(); Set<String> visited=new HashSet<>(seeds);
        seeds.forEach(seed->queue.add(new Visit(seed,List.of(seed)))); boolean truncated=false;
        while(!queue.isEmpty()) {
            Visit current=queue.removeFirst();
            List<Relation> callers=incoming.getOrDefault(current.node(),List.of());
            if(current.path().size()-1>=depth) {
                if(callers.stream().anyMatch(r->!visited.contains(r.source()))) truncated=true;
                continue;
            }
            for(Relation relation:callers) {
                if(visited.contains(relation.source())) continue;
                if(visited.size()>=10_000) return true;
                visited.add(relation.source());
                List<String> path=new ArrayList<>();path.add(relation.source());path.addAll(current.path());
                out.add(Map.of("record","impact","side",side,"entity",relation.source(),"path",path,
                        "sites",relation.sites(),"interpretation","static_possible_impact"));
                queue.add(new Visit(relation.source(),List.copyOf(path)));
            }
        } return truncated;
    }
}
