package com.anatomist.query;

import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.anatomist.store.SqliteStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0: verifies that {@code callees-of --through-callbacks} follows CALLS inside
 * anonymous-class / lambda bodies defined within a method, attributing them to the
 * outer method (template-callback pattern, e.g. SettleServiceTemplate#execute(callback)).
 *
 * Graph (mirrors imerchantsettle MerchantBillServiceImpl#settle):
 *   M#m() →CALLS→ Template#execute            (the only DIRECT call)
 *   M#m() →CONTAINS→ M#m()$anon@L1            (ANONYMOUS_CLASS)
 *   M#m()$anon@L1 →CONTAINS→ M#m()$anon@L1#process()   (METHOD)
 *   M#m()$anon@L1#process() →CALLS→ Dep#run()          (the REAL downstream)
 *   nested: M#m()$anon@L1#process() →CONTAINS→ ...$anon@L2 →CONTAINS→ ...$anon@L2#cb()
 *           ...$anon@L2#cb() →CALLS→ Dep2#deep()
 *   lambda: M#m() →CONTAINS→ M#m()$lambda@L3 (LAMBDA) →CALLS→ Dep3#fn()
 */
class CalleesThroughCallbacksTest {

    Path dbPath;

    static final String M       = "com.a.M#m()";
    static final String ANON1   = "com.a.M#m()$anon@L1";
    static final String PROCESS = "com.a.M#m()$anon@L1#process()";
    static final String ANON2   = "com.a.M#m()$anon@L1#process()$anon@L2";
    static final String CB      = "com.a.M#m()$anon@L1#process()$anon@L2#cb()";
    static final String LAMBDA  = "com.a.M#m()$lambda@L3";

    @BeforeEach
    void buildIndex(@TempDir Path tmp) {
        dbPath = tmp.resolve("cb.db");
        try (SqliteStore store = new SqliteStore(dbPath)) {
            store.initSchema();
            ExtractionResult r = new ExtractionResult();

            r.nodes.add(node(M, "m", "METHOD", "com.a"));
            r.nodes.add(node(ANON1, "$anon", "ANONYMOUS_CLASS", "com.a"));
            r.nodes.add(node(PROCESS, "process", "METHOD", "com.a"));
            r.nodes.add(node(ANON2, "$anon", "ANONYMOUS_CLASS", "com.a"));
            r.nodes.add(node(CB, "cb", "METHOD", "com.a"));
            r.nodes.add(node(LAMBDA, "lambda$m$0", "LAMBDA", "com.a"));
            r.nodes.add(node("com.t.Template#execute()", "execute", "METHOD", "com.t"));
            r.nodes.add(node("com.d.Dep#run()", "run", "METHOD", "com.d"));
            r.nodes.add(node("com.d.Dep2#deep()", "deep", "METHOD", "com.d"));
            r.nodes.add(node("com.d.Dep3#fn()", "fn", "METHOD", "com.d"));

            // The only direct CALLS from m(): the template.
            r.edges.add(calls(M, "com.t.Template#execute()", "STATIC"));

            // CONTAINS hierarchy.
            r.edges.add(contains(M, ANON1));
            r.edges.add(contains(ANON1, PROCESS));
            r.edges.add(contains(PROCESS, ANON2));
            r.edges.add(contains(ANON2, CB));
            r.edges.add(contains(M, LAMBDA));

            // CALLS hidden inside the callback bodies.
            r.edges.add(calls(PROCESS, "com.d.Dep#run()", "INTERFACE"));
            r.edges.add(calls(CB, "com.d.Dep2#deep()", "INSTANCE"));
            r.edges.add(calls(LAMBDA, "com.d.Dep3#fn()", "INSTANCE"));

            store.write(r);
        }
    }

    @Test
    void withoutFlag_callbackBodyIsInvisible() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> edges = q.calleesOf(M, 3, false);
            assertTrue(edges.stream().anyMatch(e -> "com.t.Template#execute()".equals(e.target)),
                    "direct call to Template#execute is present");
            assertFalse(edges.stream().anyMatch(e -> "com.d.Dep#run()".equals(e.target)),
                    "Dep#run inside the callback body is NOT reachable without the flag");
        }
    }

    @Test
    void withFlag_attributesCallbackCallsToOuterMethod() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> edges = q.calleesOf(M, 3, true);

            EdgeRow run = edges.stream()
                    .filter(e -> "com.d.Dep#run()".equals(e.target)).findFirst().orElse(null);
            assertNotNull(run, "Dep#run surfaces with the flag on");
            assertEquals(M, run.source, "synthesized edge is attributed to the outer method");
            assertEquals(PROCESS, run.via, "via records the physical callback body");
            assertEquals("INTERFACE", run.callKind, "original call_kind preserved");

            // Direct template call still present and NOT tagged as via.
            assertTrue(edges.stream().anyMatch(e ->
                    "com.t.Template#execute()".equals(e.target) && e.via == null),
                    "direct edge keeps via=null");
        }
    }

    @Test
    void withFlag_penetratesNestedCallbacks() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> edges = q.calleesOf(M, 3, true);
            EdgeRow deep = edges.stream()
                    .filter(e -> "com.d.Dep2#deep()".equals(e.target)).findFirst().orElse(null);
            assertNotNull(deep, "nested anon (process → $anon@L2 → cb) call surfaces");
            assertEquals(M, deep.source, "nested callback call also attributed to outer method");
            assertEquals(CB, deep.via, "via points at the nested callback body");
        }
    }

    @Test
    void withFlag_penetratesLambdaBodies() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> edges = q.calleesOf(M, 3, true);
            EdgeRow fn = edges.stream()
                    .filter(e -> "com.d.Dep3#fn()".equals(e.target)).findFirst().orElse(null);
            assertNotNull(fn, "lambda body call surfaces");
            assertEquals(LAMBDA, fn.via, "via points at the lambda body");
        }
    }

    @Test
    void withFlag_noDuplicateEdges() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> edges = q.calleesOf(M, 3, true);
            long unique = edges.stream()
                    .map(e -> e.source + "→" + e.target + "@" + e.depth).distinct().count();
            assertEquals(edges.size(), unique, "no duplicate edges with --through-callbacks");
        }
    }

    // ── P1: reverse penetration (callers-of) ──────────────────────────────

    @Test
    void callersOf_withoutFlag_reportsCallbackBodyAsCaller() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> callers = q.callersOf("com.d.Dep#run()", 3, false);
            assertTrue(callers.stream().anyMatch(e -> PROCESS.equals(e.source)),
                    "without flag, the raw caller is the callback body node");
            assertFalse(callers.stream().anyMatch(e -> M.equals(e.source)),
                    "the enclosing method is not surfaced as a caller without the flag");
        }
    }

    @Test
    void callersOf_withFlag_attributesCallToEnclosingMethod() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> callers = q.callersOf("com.d.Dep#run()", 3, true);
            EdgeRow edge = callers.stream()
                    .filter(e -> "com.d.Dep#run()".equals(e.target)).findFirst().orElse(null);
            assertNotNull(edge, "incoming call present");
            assertEquals(M, edge.source, "caller rewritten to the enclosing method");
            assertEquals(PROCESS, edge.via, "via records the callback body the call came from");
        }
    }

    @Test
    void enclosingMethod_truncatesAtSyntheticMarker() {
        assertEquals("com.a.M#m()", CallGraphService.enclosingMethod(PROCESS));
        assertEquals("com.a.M#m()", CallGraphService.enclosingMethod(CB));
        assertEquals("com.a.M#m()", CallGraphService.enclosingMethod(LAMBDA));
        assertNull(CallGraphService.enclosingMethod("com.a.Plain#method()"),
                "a non-callback method id has no enclosing callback method");
    }

    private static Node node(String id, String label, String kind, String pkg) {
        Node n = new Node();
        n.id = id; n.label = label; n.kind = kind; n.qualifiedName = id;
        n.pkg = pkg; n.sourceFile = pkg.replace('.', '/') + "/X.java";
        n.scope = "MAIN";
        return n;
    }

    private static Edge calls(String src, String tgt, String callKind) {
        Edge e = new Edge();
        e.sourceId = src; e.targetId = tgt; e.relation = "CALLS";
        e.callKind = callKind; e.isExternal = false;
        return e;
    }

    private static Edge contains(String parent, String child) {
        Edge e = new Edge();
        e.sourceId = parent; e.targetId = child; e.relation = "CONTAINS";
        e.isExternal = false;
        return e;
    }
}
