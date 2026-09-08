package com.anatomist.query;

import com.anatomist.json.Json;
import com.anatomist.query.JavaSemanticRows.DispatchResult;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JavaDispatchServiceTest {
    Connection db;
    @BeforeEach void setup() throws Exception {
        db=DriverManager.getConnection("jdbc:sqlite::memory:");
        try(var s=db.createStatement()) {
            s.execute("CREATE TABLE nodes(id TEXT,symbol_id TEXT,qualified_name TEXT,kind TEXT,module TEXT,scope TEXT,modifiers TEXT)");
            s.execute("CREATE TABLE edges(source_id TEXT,target_id TEXT,external_target_fqn TEXT,relation TEXT,is_external INTEGER DEFAULT 0,confidence TEXT DEFAULT 'EXTRACTED',resolution TEXT)");
        }
    }
    @AfterEach void close() throws Exception { db.close(); }
    private void node(String id,String kind,String... mods) throws Exception {
        try(var s=db.prepareStatement("INSERT INTO nodes VALUES(?,?,?,?,?,?,?)")) {
            s.setString(1,id);s.setString(2,id);s.setString(3,id);s.setString(4,kind);s.setString(5,".");s.setString(6,"MAIN");s.setString(7,Json.writeCompact(List.of(mods)));s.executeUpdate();
        }
    }
    private void edge(String from,String to,String kind) throws Exception {
        try(var s=db.prepareStatement("INSERT INTO edges(source_id,target_id,relation) VALUES(?,?,?)")) {
            s.setString(1,from);s.setString(2,to);s.setString(3,kind);s.executeUpdate();
        }
    }
    private Map<String,Object> site(String id,String receiver,String kind) {
        return Map.of("id","site","caller","p.Caller#run()","dispatch_kind",kind,"receiver_static_type",receiver,
                "resolved_targets",List.of(Map.of("id",id,"external",false,"resolution_status","exact")));
    }
    private DispatchResult run(Map<String,Object> site,int depth,int limit,int states) {
        return new JavaDispatchService(db).dispatch(site,"auto","workspace-closed",depth,limit,states,null,"ALL");
    }
    private Set<String> possible(DispatchResult result) {
        return result.targets().stream().filter(t->t.candidateKind().equals("possible")).map(t->t.target()).collect(java.util.stream.Collectors.toSet());
    }
    private void contract() throws Exception { node("p.I","INTERFACE");node("p.I#m()","METHOD","abstract","public"); }
    private void implementation(String name) throws Exception {
        node(name,"CLASS");node(name+"#m()","METHOD","public");edge(name,"p.I","IMPLEMENTS");edge(name+"#m()","p.I#m()","OVERRIDES");
    }

    @Test void inheritedBodyOnAbstractOwnerIsExecutableViaConcreteSubtype() throws Exception {
        contract();node("p.Base","CLASS","abstract");node("p.Base#m()","METHOD","public");node("p.Child","CLASS");
        edge("p.Base","p.I","IMPLEMENTS");edge("p.Child","p.Base","INHERITS");edge("p.Base#m()","p.I#m()","OVERRIDES");
        var result=run(site("p.I#m()","p.I","instance"),20,50,1000);
        assertEquals(Set.of("p.Base#m()"),possible(result));assertTrue(result.complete(),result.toString());
        assertFalse(result.targets().getLast().typeProof().isEmpty());assertTrue(result.targets().getLast().executable());
    }
    @Test void superclassBodyCanImplementContractInheritedBySubclass() throws Exception {
        contract();node("p.Base","CLASS");node("p.Base#m()","METHOD","public");node("p.Child","CLASS");
        edge("p.Child","p.Base","INHERITS");edge("p.Child","p.I","IMPLEMENTS");
        var result=run(site("p.I#m()","p.I","instance"),20,50,1000);
        assertEquals(Set.of("p.Base#m()"),possible(result));
        assertTrue(result.targets().getLast().reason().contains("java.inherited_implementation"));
        assertEquals(2,result.targets().getLast().typeProof().size());
    }
    @Test void receiverExcludesSiblingImplementationsAndOverloads() throws Exception {
        contract();implementation("p.Left");implementation("p.Right");node("p.Left#m(int)","METHOD","public");
        var result=run(site("p.I#m()","p.Left","instance"),20,50,1000);
        assertEquals(Set.of("p.Left#m()"),possible(result));
    }
    @Test void nearestDefaultWinsAndAbstractRedeclarationHidesIt() throws Exception {
        contract();node("p.J","INTERFACE");node("p.J#m()","METHOD","public","default");node("p.Child","CLASS");
        edge("p.J","p.I","INHERITS");edge("p.J#m()","p.I#m()","OVERRIDES");edge("p.Child","p.J","IMPLEMENTS");
        assertEquals(Set.of("p.J#m()"),possible(run(site("p.I#m()","p.I","instance"),20,50,1000)));
        node("p.Child#m()","METHOD","public","abstract");
        assertTrue(possible(run(site("p.I#m()","p.I","instance"),20,50,1000)).isEmpty());
    }
    @Test void nonvirtualKindsAndFinalPrivateMethodsRemainResolved() throws Exception {
        contract();implementation("p.Child");
        for(String kind:List.of("static","super","constructor")) {
            var result=run(site("p.I#m()","p.I",kind),20,1,1);
            assertEquals(1,result.targets().size());assertTrue(result.complete(),result.toString());
        }
        for(String modifier:List.of("final","private","static")) {
            node("p.Child#"+modifier+"()","METHOD",modifier);
            var result=run(site("p.Child#"+modifier+"()","p.Child","instance"),20,1,1);
            assertEquals("exact",result.targets().getFirst().algorithm());assertTrue(result.complete());
        }
    }
    @Test void actualLimitsDifferFromExhaustionExactlyAtLimit() throws Exception {
        contract();implementation("p.A");implementation("p.B");var site=site("p.I#m()","p.I","instance");
        var full=run(site,20,3,1000);assertEquals(3,full.targets().size());assertFalse(full.truncated(),full.toString());
        assertTrue(run(site,20,2,1000).reasons().contains("DISPATCH_TARGET_LIMIT"));
        assertFalse(run(site,20,3,full.states()).truncated());
        assertTrue(run(site,20,3,full.states()-1).reasons().contains("DISPATCH_STATE_LIMIT"));
        node("p.C","CLASS");edge("p.C","p.B","INHERITS");
        assertTrue(run(site,1,50,1000).reasons().contains("DISPATCH_DEPTH_LIMIT"));
    }
    @Test void configuredEvidenceDoesNotRemoveOtherCandidates() throws Exception {
        contract();implementation("p.A");implementation("p.B");
        edge("p.Caller","p.I","INJECTS");edge("p.Caller","p.A","WIRES");
        var result=run(site("p.I#m()","p.I","instance"),20,50,1000);
        assertEquals(Set.of("p.A#m()","p.B#m()"),possible(result));
        assertTrue(result.targets().stream().filter(t->t.target().equals("p.A#m()")).findFirst().orElseThrow().reason().contains("configuration.binding"));
    }
    @Test void openWorldAndMissingTypesAreNotTruncation() throws Exception {
        contract();var result=new JavaDispatchService(db).dispatch(site("p.I#m()","missing.Type","instance"),"auto","workspace-open",20,50,1000,null,"ALL");
        assertFalse(result.complete());assertFalse(result.truncated());
        assertTrue(result.reasons().containsAll(List.of("DISPATCH_OPEN_WORLD","DISPATCH_RECEIVER_UNRESOLVED")));
        var external=run(site("missing.Type#m()","missing.Type","instance"),20,50,1000);
        assertTrue(external.reasons().contains("DISPATCH_EXTERNAL_TARGET"));assertFalse(external.truncated());
    }
}
