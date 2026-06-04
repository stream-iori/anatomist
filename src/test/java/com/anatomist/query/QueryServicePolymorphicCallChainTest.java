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
 * L2 test: verifies that callees-of / callers-of / call-path pierce
 * interface/abstract method dispatch via OVERRIDES edges.
 *
 * Graph:
 *   Controller#handle →CALLS→ Service#process (interface method)
 *   ServiceImpl#process →OVERRIDES→ Service#process
 *   ServiceImpl#process →CALLS→ Repository#save (interface method)
 *   RepositoryImpl#save →OVERRIDES→ Repository#save
 *   RepositoryImpl#save →CALLS→ Logger#log
 *
 * Diamond: ServiceImpl2#process →OVERRIDES→ Service#process (second impl)
 */
class QueryServicePolymorphicCallChainTest {

    Path dbPath;

    @BeforeEach
    void buildIndex(@TempDir Path tmp) {
        dbPath = tmp.resolve("poly.db");
        try (SqliteStore store = new SqliteStore(dbPath)) {
            store.initSchema();
            ExtractionResult r = new ExtractionResult();

            r.nodes.add(method("com.a.Controller#handle()", "handle", "com.a"));
            r.nodes.add(method("com.a.Service#process()", "process", "com.a"));
            r.nodes.add(method("com.b.ServiceImpl#process()", "process", "com.b"));
            r.nodes.add(method("com.c.ServiceImpl2#process()", "process", "com.c"));
            r.nodes.add(method("com.d.Repository#save()", "save", "com.d"));
            r.nodes.add(method("com.e.RepositoryImpl#save()", "save", "com.e"));
            r.nodes.add(method("com.f.Logger#log()", "log", "com.f"));

            // Controller#handle → Service#process (CALLS, interface dispatch)
            r.edges.add(calls("com.a.Controller#handle()", "com.a.Service#process()", "INTERFACE"));
            // ServiceImpl#process overrides Service#process
            r.edges.add(overrides("com.b.ServiceImpl#process()", "com.a.Service#process()"));
            // ServiceImpl2#process also overrides Service#process (diamond)
            r.edges.add(overrides("com.c.ServiceImpl2#process()", "com.a.Service#process()"));
            // ServiceImpl#process → Repository#save (CALLS)
            r.edges.add(calls("com.b.ServiceImpl#process()", "com.d.Repository#save()", "INTERFACE"));
            // RepositoryImpl#save overrides Repository#save
            r.edges.add(overrides("com.e.RepositoryImpl#save()", "com.d.Repository#save()"));
            // RepositoryImpl#save → Logger#log (CALLS)
            r.edges.add(calls("com.e.RepositoryImpl#save()", "com.f.Logger#log()", "INSTANCE"));

            store.write(r);
        }
    }

    @Test
    void calleesOf_piercesInterfaceDispatch() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> edges = q.calleesOf("com.a.Controller#handle()", 5);

            assertTrue(edges.stream().anyMatch(e ->
                    "CALLS".equals(e.relation)
                    && "com.a.Controller#handle()".equals(e.source)
                    && "com.a.Service#process()".equals(e.target)),
                    "depth 1: Controller → Service#process (CALLS)");

            assertTrue(edges.stream().anyMatch(e ->
                    "OVERRIDES".equals(e.relation)
                    && "com.a.Service#process()".equals(e.source)
                    && "com.b.ServiceImpl#process()".equals(e.target)),
                    "depth 1: Service#process → ServiceImpl#process (OVERRIDES dispatch)");

            assertTrue(edges.stream().anyMatch(e ->
                    "OVERRIDES".equals(e.relation)
                    && "com.a.Service#process()".equals(e.source)
                    && "com.c.ServiceImpl2#process()".equals(e.target)),
                    "depth 1: Service#process → ServiceImpl2#process (diamond, second impl)");

            assertTrue(edges.stream().anyMatch(e ->
                    "CALLS".equals(e.relation)
                    && "com.b.ServiceImpl#process()".equals(e.source)
                    && "com.d.Repository#save()".equals(e.target)),
                    "depth 2: ServiceImpl#process → Repository#save (CALLS)");

            assertTrue(edges.stream().anyMatch(e ->
                    "CALLS".equals(e.relation)
                    && "com.e.RepositoryImpl#save()".equals(e.source)
                    && "com.f.Logger#log()".equals(e.target)),
                    "deep: RepositoryImpl#save → Logger#log (pierced through 2 interface layers)");
        }
    }

    @Test
    void calleesOf_interfaceMethodSeed_dispatchesToImpls() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> edges = q.calleesOf("com.a.Service#process()", 3);

            assertTrue(edges.stream().anyMatch(e ->
                    "OVERRIDES".equals(e.relation)
                    && "com.a.Service#process()".equals(e.source)
                    && "com.b.ServiceImpl#process()".equals(e.target)),
                    "seed is interface method → dispatches to impl via OVERRIDES");

            assertTrue(edges.stream().anyMatch(e ->
                    "CALLS".equals(e.relation)
                    && "com.b.ServiceImpl#process()".equals(e.source)
                    && "com.d.Repository#save()".equals(e.target)),
                    "impl's callees are reachable after dispatch");
        }
    }

    @Test
    void callersOf_piercesFromImplToInterfaceCallers() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> edges = q.callersOf("com.b.ServiceImpl#process()", 3);

            assertTrue(edges.stream().anyMatch(e ->
                    "OVERRIDES".equals(e.relation)
                    && "com.a.Service#process()".equals(e.source)
                    && "com.b.ServiceImpl#process()".equals(e.target)),
                    "bridge: Service#process → ServiceImpl#process (OVERRIDES)");

            assertTrue(edges.stream().anyMatch(e ->
                    "CALLS".equals(e.relation)
                    && "com.a.Controller#handle()".equals(e.source)
                    && "com.a.Service#process()".equals(e.target)),
                    "callers of iface method are reachable via OVERRIDES bridge");
        }
    }

    @Test
    void callersOf_deepChainPiercesMultipleLayers() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> edges = q.callersOf("com.e.RepositoryImpl#save()", 5);

            assertTrue(edges.stream().anyMatch(e ->
                    "com.a.Controller#handle()".equals(e.source)),
                    "Controller#handle reachable through 2 layers of interface piercing");
        }
    }

    @Test
    void callPath_piercesInterface() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> path = q.callPath(
                    "com.a.Controller#handle()",
                    "com.e.RepositoryImpl#save()", 10);

            assertFalse(path.isEmpty(), "path should be found through interface dispatch");

            assertTrue(path.stream().anyMatch(e -> "OVERRIDES".equals(e.relation)),
                    "path contains at least one OVERRIDES hop");

            assertEquals("com.a.Controller#handle()", path.get(0).source,
                    "path starts from Controller#handle");

            String lastTarget = path.get(path.size() - 1).target;
            assertEquals("com.e.RepositoryImpl#save()", lastTarget,
                    "path ends at RepositoryImpl#save");
        }
    }

    @Test
    void callPath_unreachableWithoutOverrides_stillFindsPath() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> path = q.callPath(
                    "com.a.Controller#handle()",
                    "com.f.Logger#log()", 10);

            assertFalse(path.isEmpty(),
                    "Logger#log reachable through Controller → Service(iface) → ServiceImpl → Repository(iface) → RepositoryImpl → Logger");
        }
    }

    @Test
    void noDuplicateEdges() {
        try (QueryService q = new QueryService(dbPath)) {
            List<EdgeRow> edges = q.calleesOf("com.a.Controller#handle()", 5);
            long uniqueCount = edges.stream()
                    .map(e -> e.source + "→" + e.target + "@" + e.depth)
                    .distinct()
                    .count();
            assertEquals(edges.size(), uniqueCount, "no duplicate edges in output");
        }
    }

    private static Node method(String id, String label, String pkg) {
        Node n = new Node();
        n.id = id; n.label = label; n.kind = "METHOD"; n.qualifiedName = id;
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

    private static Edge overrides(String impl, String iface) {
        Edge e = new Edge();
        e.sourceId = impl; e.targetId = iface; e.relation = "OVERRIDES";
        e.isExternal = false;
        return e;
    }
}
