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

/** L2 test for {@link QueryService#overview()} / {@link QueryService#classDepsInternal()}.
 *  Builds a tiny index in a temp db directly via {@link SqliteStore}. */
class QueryServiceOverviewTest {

    Path dbPath;

    @BeforeEach
    void buildIndex(@TempDir Path tmp) {
        dbPath = tmp.resolve("ov.db");
        try (SqliteStore store = new SqliteStore(dbPath)) {
            store.initSchema();
            ExtractionResult r = new ExtractionResult();
            r.nodes.add(type("com.a.Foo", "Foo", "CLASS", "com.a"));
            r.nodes.add(type("com.a.Bar", "Bar", "INTERFACE", "com.a"));
            r.nodes.add(type("com.b.Baz", "Baz", "CLASS", "com.b"));
            r.nodes.add(method("com.a.Foo#run()", "run", "com.a"));
            r.nodes.add(method("com.b.Baz#go()", "go", "com.b"));
            // internal CALLS Foo#run -> Baz#go ; cross-package
            r.edges.add(internal("com.a.Foo#run()", "com.b.Baz#go()", "CALLS"));
            // internal REFERENCES Foo -> Baz ; cross-package (class-level)
            r.edges.add(internal("com.a.Foo", "com.b.Baz", "REFERENCES"));
            // external CALLS Foo#run -> java.util.List#add
            r.edges.add(external("com.a.Foo#run()", "java.util.List#add(java.lang.Object)", "CALLS"));
            store.write(r);
        }
    }

    @Test
    void overview_countsKindsEdgesAndPackages() {
        try (QueryService q = new QueryService(dbPath)) {
            OverviewResult ov = q.overview();
            assertEquals(2L, ov.kindCounts.get("CLASS"));
            assertEquals(1L, ov.kindCounts.get("INTERFACE"));
            assertEquals(2L, ov.kindCounts.get("METHOD"));

            assertEquals(2L, ov.internalEdgeCounts.values().stream().mapToLong(Long::longValue).sum());
            assertEquals(1L, ov.externalEdgeCounts.get("CALLS"));

            PackageStat a = ov.packages.stream().filter(p -> "com.a".equals(p.name)).findFirst().orElseThrow();
            assertEquals(2L, a.types);
            assertEquals(1L, a.methods);

            // packageDeps: com.a -> com.b should be present (internal, cross-package)
            assertTrue(ov.packageDeps.stream().anyMatch(d ->
                    "com.a".equals(d.get("source_package")) && "com.b".equals(d.get("target_package"))));
        }
    }

    @Test
    void classDepsInternal_returnsCrossClassEdgesWithPackages() {
        try (QueryService q = new QueryService(dbPath)) {
            List<ClassEdge> deps = q.classDepsInternal(0);
            assertTrue(deps.stream().anyMatch(d ->
                    "com.a.Foo".equals(d.source()) && "com.b.Baz".equals(d.target())
                            && "com.a".equals(d.sourcePackage())
                            && "com.b".equals(d.targetPackage())));
            // external edge must not appear
            assertFalse(deps.stream().anyMatch(d -> String.valueOf(d.target()).startsWith("java.")));
        }
    }

    @Test
    void classDepsInternal_respectsMaxEdges() {
        try (QueryService q = new QueryService(dbPath)) {
            assertEquals(1, q.classDepsInternal(1).size());
        }
    }

    private static Node type(String id, String label, String kind, String pkg) {
        Node n = new Node();
        n.id = id; n.label = label; n.kind = kind; n.qualifiedName = id;
        n.pkg = pkg; n.sourceFile = pkg.replace('.', '/') + "/" + label + ".java";
        n.scope = "MAIN";
        return n;
    }

    private static Node method(String id, String label, String pkg) {
        Node n = new Node();
        n.id = id; n.label = label; n.kind = "METHOD"; n.qualifiedName = id;
        n.pkg = pkg; n.sourceFile = pkg.replace('.', '/') + "/X.java";
        n.scope = "MAIN";
        return n;
    }

    private static Edge internal(String src, String tgt, String rel) {
        Edge e = new Edge();
        e.sourceId = src; e.targetId = tgt; e.relation = rel; e.isExternal = false;
        return e;
    }

    private static Edge external(String src, String fqn, String rel) {
        Edge e = new Edge();
        e.sourceId = src; e.externalTargetFqn = fqn; e.relation = rel; e.isExternal = true;
        return e;
    }
}
