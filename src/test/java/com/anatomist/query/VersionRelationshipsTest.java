package com.anatomist.query;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VersionRelationshipsTest {
    private static DiffNavigation.Declaration node(String id,String scope,String module) {
        return new DiffNavigation.Declaration(id,id,"METHOD",module,scope,"A.java",1,1,5,1,false);
    }
    private static Map<String,VersionRelationships.Relation> graph(String... edges) {
        Map<String,VersionRelationships.Relation> graph=new TreeMap<>();
        for(String edge:edges) {
            var parts=edge.split(">");
            graph.put(edge,new VersionRelationships.Relation(Map.of("source",parts[0],"target",parts[1],"relation","CALLS"),1,List.of()));
        } return graph;
    }
    private static Map<String,DiffNavigation.Declaration> nodes(String... ids) {
        Map<String,DiffNavigation.Declaration> nodes=new TreeMap<>();for(String id:ids) nodes.put(id,node(id,"MAIN","api"));return nodes;
    }
    @Test void sharedCallerRetainsBothOrigins() {
        var out=new ArrayList<Map<String,Object>>();
        assertTrue(VersionRelationships.impacts("target","s",graph("caller>a","caller>b"),Set.of("a","b"),
                nodes("a","b","caller"),"MAIN",null,3,out).isEmpty());
        assertEquals(2,out.size());
        assertEquals(Set.of("a","b"),out.stream().map(r->((Map<?,?>)r.get("origin")).get("id")).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void changedCallersAndCyclesKeepOtherOriginsButNotSelf() {
        var out=new ArrayList<Map<String,Object>>();
        assertTrue(VersionRelationships.impacts("base","s",graph("a>b","b>a"),Set.of("a","b"),nodes("a","b"),"MAIN",null,3,out).isEmpty());
        assertEquals(2,out.size());
        assertTrue(out.stream().allMatch(r->((List<?>)r.get("path")).size()==2));
    }
    @Test void moduleAndScopeSelectionDoesNotCutIntermediatePaths() {
        var declarations=nodes("seed","middle","test");declarations.put("middle",node("middle","MAIN","bridge"));
        declarations.put("test",node("test","TEST","tests"));var out=new ArrayList<Map<String,Object>>();
        assertTrue(VersionRelationships.impacts("target","s",graph("test>middle","middle>seed"),Set.of("seed"),
                declarations,"TEST","tests",3,out).isEmpty());
        assertEquals(1,out.size());assertEquals(List.of("test","middle","seed"),out.getFirst().get("path"));
    }
    @Test void shortestRepresentativeIsDeterministic() {
        var out=new ArrayList<Map<String,Object>>();
        VersionRelationships.impacts("target","s",graph("c>b","b>s","c>a","a>s","d>c","d>s"),Set.of("s"),
                nodes("a","b","c","d","s"),"MAIN",null,5,out);
        assertEquals(List.of("c","a","s"),out.stream().filter(r->r.get("entity").equals("c")).findFirst().orElseThrow().get("path"));
        assertEquals(List.of("d","s"),out.stream().filter(r->r.get("entity").equals("d")).findFirst().orElseThrow().get("path"));
    }
    @Test void depthZeroDisclosesTruncation() {
        var out=new ArrayList<Map<String,Object>>();
        assertEquals(Set.of("DEPTH_LIMIT"),VersionRelationships.impacts("base","s",graph("c>s"),Set.of("s"),nodes("c","s"),"MAIN",null,0,out));
        assertTrue(out.isEmpty());
    }
    @Test void entityAndStateBudgetsAreIndependent() {
        var graph=graph("c>a","c>b");var nodes=nodes("a","b","c");
        assertEquals(Set.of("ENTITY_LIMIT"),VersionRelationships.impacts("base","s",graph,Set.of("a","b"),nodes,"MAIN",null,3,new ArrayList<>(),1,100));
        assertEquals(Set.of("STATE_LIMIT"),VersionRelationships.impacts("base","s",graph,Set.of("a","b"),nodes,"MAIN",null,3,new ArrayList<>(),100,2));
    }
    @Test void missingAndFieldSeedsDoNotBecomeCalls() {
        var declarations=nodes("caller");declarations.put("field",new DiffNavigation.Declaration("field","field","FIELD",".","MAIN","A.java",0,0,0,0,false));
        var out=new ArrayList<Map<String,Object>>();
        assertTrue(VersionRelationships.impacts("base","s",graph("caller>field"),Set.of("missing","field"),declarations,"ALL",null,3,out).isEmpty());
        assertTrue(out.isEmpty());
    }

    @Test void equallyShortResolvedPathWinsEvenWhenCandidateSortsFirst() {
        var graph=graph("caller>a","a>seed","caller>z","z>seed");
        var candidate=graph.get("a>seed");
        graph.put("a>seed",new VersionRelationships.Relation(candidate.fields(),1,List.of(),Map.of("candidate_kind","possible")));
        var out=new ArrayList<Map<String,Object>>();
        VersionRelationships.impacts("target","s",graph,Set.of("seed"),nodes("seed","a","z","caller"),"ALL",null,3,out);
        var caller=out.stream().filter(r->r.get("entity").equals("caller")).findFirst().orElseThrow();
        assertEquals(List.of("caller","z","seed"),caller.get("path"));
        assertEquals(false,caller.get("contains_possible_dispatch"));
        assertEquals(true,out.stream().filter(r->r.get("entity").equals("a")).findFirst().orElseThrow().get("contains_possible_dispatch"));
    }
}
