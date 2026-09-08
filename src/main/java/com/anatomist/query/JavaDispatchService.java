package com.anatomist.query;

import com.anatomist.json.Json;
import com.anatomist.query.JavaSemanticRows.*;
import com.anatomist.query.semantic.SemanticIdentity;
import java.sql.*;
import java.util.*;

/** Snapshot-local Java dispatch. Declarations, callable proofs and runtime type proofs stay distinct. */
final class JavaDispatchService {
    private record Declaration(String id,String symbol,String name,String kind,String module,String scope,Set<String> modifiers) {
        String owner() { int at=id.indexOf('#');return at<0?id:id.substring(0,at); }
        String slot() { int at=symbol.indexOf('#');return at<0?symbol:symbol.substring(at+1); }
        boolean concreteType() { return Set.of("CLASS","ENUM","RECORD","ANONYMOUS_CLASS").contains(kind) && !modifiers.contains("abstract"); }
        boolean body() { return kind.equals("METHOD") && !modifiers.contains("abstract"); }
    }
    private final Connection connection;
    private final Map<String,Declaration> declarations=new TreeMap<>();
    private final Map<String,List<Declaration>> methods=new HashMap<>();
    private final Map<String,List<TypeRelation>> parents=new HashMap<>(),children=new HashMap<>();
    private final Map<String,List<CallableRelation>> overrides=new HashMap<>();
    private final Map<String,Set<String>> bindings=new HashMap<>();

    JavaDispatchService(Connection connection) { this.connection=connection;read(); }

    private void read() {
        try(Statement s=connection.createStatement();ResultSet r=s.executeQuery(
                "SELECT id,symbol_id,qualified_name,kind,module,scope,modifiers FROM nodes "
                + "WHERE kind IN ('CLASS','INTERFACE','ENUM','RECORD','ANNOTATION','ANONYMOUS_CLASS','METHOD','CONSTRUCTOR') ORDER BY id")) {
            while(r.next()) {
                Set<String> mods=new HashSet<>();Object parsed=Json.parseTree(Objects.toString(r.getString(7),"[]"));
                if(parsed instanceof List<?> values) values.forEach(v->mods.add(v.toString()));
                var d=new Declaration(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),Set.copyOf(mods));
                declarations.put(d.id(),d);
                if(d.kind().equals("METHOD")) methods.computeIfAbsent(d.owner(),k->new ArrayList<>()).add(d);
            }
        } catch(SQLException e) { throw new RuntimeException("failed to read dispatch declarations",e); }
        try(Statement s=connection.createStatement();ResultSet r=s.executeQuery(
                "SELECT source_id,coalesce(target_id,external_target_fqn),relation,is_external,confidence,resolution FROM edges "
                + "WHERE relation IN ('INHERITS','IMPLEMENTS','OVERRIDES') ORDER BY source_id,relation,target_id,external_target_fqn")) {
            while(r.next()) {
                String from=r.getString(1),to=r.getString(2),relation=r.getString(3);
                if(from==null || to==null) continue;
                var a=declarations.get(from);var b=declarations.get(to);boolean external=r.getBoolean(4);
                String confidence=Objects.toString(r.getString(5),"").toLowerCase(Locale.ROOT);
                String resolution="ambiguous".equals(confidence)?"ambiguous":"inferred".equals(confidence)?"heuristic":"exact";
                if(r.getString(6)!=null) resolution="heuristic";
                if(relation.equals("OVERRIDES")) {
                    var owner=b==null?null:declarations.get(b.owner());boolean contract=owner!=null && owner.kind().equals("INTERFACE");
                    String semantic=contract?"IMPLEMENTS_CONTRACT":"OVERRIDES",mechanism=contract?"java.interface_method":"java.override";
                    var edge=new CallableRelation("callablerel:sha256:"+SemanticIdentity.sha256(semantic+"\n"+mechanism+"\n"+from+"\n"+to),
                            semantic,mechanism,from,to,a==null?from:a.name(),b==null?to:b.name(),external,"extracted".equals(confidence)?"extracted":"derived",resolution,confidence);
                    overrides.computeIfAbsent(to,k->new ArrayList<>()).add(edge);
                } else {
                    String semantic=relation.equals("IMPLEMENTS")?"conforms-to":"subtype-of";
                    String mechanism=relation.equals("IMPLEMENTS")?"java.implements":a!=null && a.kind().equals("INTERFACE")?"java.extends_interface":"java.extends_class";
                    var edge=new TypeRelation("typerel:sha256:"+SemanticIdentity.sha256(semantic+"\n"+mechanism+"\n"+from+"\n"+to),
                            semantic,mechanism,from,to,a==null?from:a.name(),b==null?to:b.name(),external,true,"extracted".equals(confidence)?"extracted":"derived",resolution,confidence);
                    parents.computeIfAbsent(from,k->new ArrayList<>()).add(edge);
                    if(!external) children.computeIfAbsent(to,k->new ArrayList<>()).add(edge);
                }
            }
        } catch(SQLException e) { throw new RuntimeException("failed to read dispatch hierarchy",e); }
    }

    private static final class Search {
        final int maxDepth,limit,budget;
        int states;
        boolean truncated;
        final Set<String> reasons=new TreeSet<>();
        Search(int depth,int limit,int budget,String world) {
            this.maxDepth=depth;this.limit=limit;this.budget=budget;
            if(!world.equals("workspace-closed")) reasons.add("DISPATCH_OPEN_WORLD");
        }
        boolean step() {
            if(states>=budget) { cut("DISPATCH_STATE_LIMIT");return false; }
            states++;return true;
        }
        void cut(String reason) { truncated=true;reasons.add(reason); }
    }

    DispatchResult dispatch(Map<String,Object> site,String requested,String world,int depth,int limit,int budget,String module,String scope) {
        if(depth<1 || limit<1 || budget<0) throw new IllegalArgumentException("Invalid dispatch limits");
        Search search=new Search(depth,limit,budget,world);
        String siteId=Objects.toString(site.get("id"),""),caller=Objects.toString(site.get("caller"),"");
        String kind=Objects.toString(site.get("dispatch_kind"),"unknown").toLowerCase(Locale.ROOT);
        Map<String,DispatchTarget> out=new LinkedHashMap<>();
        if(!(site.get("resolved_targets") instanceof List<?> targets) || targets.isEmpty()) search.reasons.add("DISPATCH_TARGET_UNRESOLVED");
        else for(Object value:targets) {
            if(!(value instanceof Map<?,?> target) || target.get("id")==null) { search.reasons.add("DISPATCH_TARGET_UNRESOLVED");continue; }
            if(!search.step()) break;
            String id=target.get("id").toString();Declaration resolved=declarations.get(id);
            boolean external=Boolean.TRUE.equals(target.get("external")) || resolved==null;
            boolean exact=requested.equals("exact") || exact(kind,resolved);
            String algorithm=exact?"exact":"CHA";
            String resolution=external?"heuristic":Objects.toString(target.get("resolution_status"),"exact");
            if(external) search.reasons.add("DISPATCH_EXTERNAL_TARGET");
            if(!resolution.equals("exact")) search.reasons.add("DISPATCH_RESOLUTION_INCOMPLETE");
            out.putIfAbsent(id,row(siteId,caller,id,resolved==null?id:resolved.name(),"resolved",kind,algorithm,world,
                    external?null:!resolved.modifiers().contains("abstract"),resolution,List.of("java.static_resolution"),List.of(),List.of()));
            if(out.size()>limit) { search.cut("DISPATCH_TARGET_LIMIT");break; }
            if(external || exact) continue;
            if(!Set.of("instance","virtual","interface","method_reference").contains(kind)) {
                search.reasons.add("DISPATCH_KIND_UNSUPPORTED");continue;
            }
            String receiver=receiver(site,resolved,search);
            Map<String,List<CallableRelation>> candidates=callableCandidates(id,search);
            Map<String,List<TypeRelation>> runtimeTypes=walkTypes(receiver,false,search);
            Set<String> configured=configured(caller,resolved.owner());
            for(var runtime:runtimeTypes.entrySet()) {
                Declaration type=declarations.get(runtime.getKey());
                if(type==null || !type.concreteType()) continue;
                Map<String,List<TypeRelation>> ancestry=walkTypes(type.id(),true,search);
                for(Declaration implementation:implementations(resolved,candidates,ancestry,search)) {
                    if(implementation.id().equals(id) || out.containsKey(implementation.id())) continue;
                    if(!scope.equals("ALL") && !scope.equals(implementation.scope()) || module!=null && !module.equals(implementation.module())) continue;
                    List<TypeRelation> proof=new ArrayList<>(runtime.getValue());proof.addAll(ancestry.getOrDefault(implementation.owner(),List.of()));
                    List<String> reasons=new ArrayList<>(List.of("java.runtime_type"));
                    var callableProof=candidates.getOrDefault(implementation.id(),List.of());
                    reasons.add(callableProof.isEmpty()?"java.inherited_implementation":"java.override");
                    if(configured.contains(type.id())) reasons.add("configuration.binding");
                    out.put(implementation.id(),row(siteId,caller,implementation.id(),implementation.name(),"possible",kind,"CHA",world,
                            true,"heuristic",reasons,callableProof,List.copyOf(new LinkedHashSet<>(proof))));
                    if(out.size()>limit) { search.cut("DISPATCH_TARGET_LIMIT");break; }
                }
                if(out.size()>limit || search.reasons.contains("DISPATCH_STATE_LIMIT")) break;
            }
            if(out.size()>limit) break;
        }
        return new DispatchResult(out.values().stream().limit(limit).toList(),List.copyOf(search.reasons),search.truncated,search.states);
    }

    private String receiver(Map<String,Object> site,Declaration target,Search search) {
        Object raw=site.get("receiver_static_type");
        if(raw==null) return target.owner();
        String name=raw.toString();int generic=name.indexOf('<');if(generic>=0) name=name.substring(0,generic);
        if(declarations.containsKey(name)) return name;
        String selected=name;
        List<Declaration> matches=declarations.values().stream().filter(d->d.symbol().equals(selected) && !d.id().contains("#")).toList();
        if(matches.size()==1) return matches.getFirst().id();
        search.reasons.add("DISPATCH_RECEIVER_UNRESOLVED");return target.owner();
    }

    private Map<String,List<CallableRelation>> callableCandidates(String target,Search search) {
        Map<String,List<CallableRelation>> out=new LinkedHashMap<>();out.put(target,List.of());
        Deque<String> queue=new ArrayDeque<>();queue.add(target);
        while(!queue.isEmpty()) {
            String current=queue.removeFirst();var path=out.get(current);
            for(var edge:overrides.getOrDefault(current,List.of())) {
                if(out.containsKey(edge.subject())) continue;
                if(path.size()>=search.maxDepth) { search.cut("DISPATCH_DEPTH_LIMIT");continue; }
                if(!search.step()) return out;
                List<CallableRelation> next=new ArrayList<>(path);next.add(edge);out.put(edge.subject(),List.copyOf(next));queue.addLast(edge.subject());
                if(!edge.resolutionStatus().equals("exact")) search.reasons.add("DISPATCH_HIERARCHY_INCOMPLETE");
            }
        }
        return out;
    }

    private Map<String,List<TypeRelation>> walkTypes(String start,boolean upward,Search search) {
        Map<String,List<TypeRelation>> out=new LinkedHashMap<>();out.put(start,List.of());
        Deque<String> queue=new ArrayDeque<>();queue.add(start);
        while(!queue.isEmpty()) {
            String current=queue.removeFirst();var path=out.get(current);
            for(var edge:(upward?parents:children).getOrDefault(current,List.of())) {
                String next=upward?edge.object():edge.subject();if(out.containsKey(next)) continue;
                if(edge.externalObject() || !declarations.containsKey(next)) {
                    if(!edge.object().equals("java.lang.Object")) search.reasons.add("DISPATCH_HIERARCHY_INCOMPLETE");
                    continue;
                }
                if(path.size()>=search.maxDepth) { search.cut("DISPATCH_DEPTH_LIMIT");continue; }
                if(!search.step()) return out;
                List<TypeRelation> proof=new ArrayList<>(path);proof.add(edge);out.put(next,List.copyOf(proof));queue.addLast(next);
                if(!edge.resolutionStatus().equals("exact")) search.reasons.add("DISPATCH_HIERARCHY_INCOMPLETE");
            }
        }
        return out;
    }

    private List<Declaration> implementations(Declaration target,Map<String,List<CallableRelation>> candidates,
                                              Map<String,List<TypeRelation>> ancestry,Search search) {
        List<Declaration> matches=new ArrayList<>();
        for(String owner:ancestry.keySet()) for(var method:methods.getOrDefault(owner,List.of())) {
            if(!method.slot().equals(target.slot()) && !candidates.containsKey(method.id())) continue;
            if(method.modifiers().contains("static") || method.modifiers().contains("private")) continue;
            matches.add(method);
        }
        // A class declaration, including an abstract declaration, takes precedence over defaults.
        var classes=matches.stream().filter(d->!declarations.get(d.owner()).kind().equals("INTERFACE"))
                .sorted(Comparator.comparingInt((Declaration d)->ancestry.get(d.owner()).size()).thenComparing(Declaration::id)).toList();
        if(!classes.isEmpty()) return classes.getFirst().body()?List.of(classes.getFirst()):List.of();
        List<Declaration> defaults=new ArrayList<>();
        for(var candidate:matches) {
            boolean shadowed=false;
            for(var other:matches) if(!candidate.id().equals(other.id()) && walkTypes(other.owner(),true,search).containsKey(candidate.owner())) { shadowed=true;break; }
            if(!shadowed && candidate.body()) defaults.add(candidate);
        }
        if(defaults.size()>1) search.reasons.add("DISPATCH_AMBIGUOUS_IMPLEMENTATION");
        return defaults;
    }

    private boolean exact(String kind,Declaration target) {
        if(Set.of("static","super","constructor").contains(kind)) return true;
        if(target==null) return false;
        if(target.kind().equals("CONSTRUCTOR") || target.modifiers().stream().anyMatch(Set.of("static","private","final")::contains)) return true;
        var owner=declarations.get(target.owner());return owner!=null && owner.modifiers().contains("final");
    }

    private Set<String> configured(String caller,String declared) {
        int hash=caller.indexOf('#');if(hash<0) return Set.of();String owner=caller.substring(0,hash);
        return bindings.computeIfAbsent(owner+"\n"+declared,key->{
            try(PreparedStatement s=connection.prepareStatement("SELECT DISTINCT w.target_id FROM edges i JOIN edges w "
                    + "ON w.source_id=i.source_id AND w.relation='WIRES' AND w.is_external=0 "
                    + "WHERE i.relation='INJECTS' AND i.is_external=0 AND i.source_id=? AND i.target_id=?")) {
                s.setString(1,owner);s.setString(2,declared);Set<String> out=new TreeSet<>();
                try(ResultSet r=s.executeQuery()) { while(r.next()) out.add(r.getString(1)); }return Set.copyOf(out);
            } catch(SQLException e) { throw new RuntimeException("failed to read dispatch binding evidence",e); }
        });
    }

    private DispatchTarget row(String site,String caller,String target,String name,String candidate,String kind,String algorithm,String world,
                               Boolean executable,String resolution,List<String> reason,List<CallableRelation> proof,List<TypeRelation> typeProof) {
        String mechanism=algorithm.equals("exact")?switch(kind) { case "static"->"java.static";case "super"->"java.super";case "constructor"->"java.constructor";default->"java.exact_dispatch"; }
                :kind.equals("interface")?"java.interface_dispatch":"java.virtual_dispatch";
        var declaration=declarations.get(target);var owner=declaration==null?null:declarations.get(declaration.owner());
        return new DispatchTarget("dispatch:sha256:"+SemanticIdentity.sha256(site+"\n"+target+"\n"+candidate+"\n"+algorithm+"\n"+world),
                site,caller,target,name,candidate,mechanism,executable,owner==null?"unknown":owner.concreteType()?"yes":"no",algorithm,world,resolution,reason,proof,typeProof);
    }
}
