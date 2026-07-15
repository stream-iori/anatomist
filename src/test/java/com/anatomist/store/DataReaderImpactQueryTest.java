package com.anatomist.store;

import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataReaderImpactQueryTest {

    @Test
    void ownerAndExternalPrefixQueriesKeepExactSemanticsAndUseIndexes(@TempDir Path tmp) throws Exception {
        try (SqliteStore store = new SqliteStore(tmp.resolve("index.db"))) {
            store.initSchema();
            ExtractionResult facts = new ExtractionResult();
            facts.nodes.add(node("p.Source", "Source.java"));
            facts.nodes.add(node("p.Owner", "Owner.java"));
            facts.nodes.add(node("p.Owner#m()", "Owner.java"));
            facts.nodes.add(node("p.Owner2#m()", "Owner2.java"));
            facts.nodes.add(node("p.Impl", "Impl.java"));
            facts.nodes.add(node("p.SubImpl", "SubImpl.java"));
            facts.edges.add(edge("p.Source", "p.Owner#m()", "UsesOwner.java"));
            facts.edges.add(edge("p.Source", "p.Owner2#m()", "UsesOwner2.java"));
            facts.edges.add(externalEdge("p.Source", "p.External#call()", "UsesExternal.java"));
            facts.edges.add(externalEdge("p.Source", "p.External2#call()", "UsesExternal2.java"));
            facts.edges.add(externalEdge("p.Source", "p.External", "UsesExternalType.java"));
            facts.edges.add(externalEdge("p.Source", "p.External2", "UsesExternalType2.java"));
            facts.edges.add(hierarchyEdge("p.Impl", "p.Owner", "IMPLEMENTS"));
            facts.edges.add(hierarchyEdge("p.SubImpl", "p.Impl", "INHERITS"));
            store.write(facts);

            assertEquals(Set.of("UsesOwner.java"),
                    store.sourceFilesReferencingOwnerIds(Set.of("p.Owner")));
            assertEquals(Set.of("UsesExternal.java"),
                    store.sourceFilesMatchingExternalTargets(Set.of("p.External#")));
            assertEquals(Set.of("UsesExternalType.java"),
                    store.sourceFilesMatchingExactExternalTargets(Set.of("p.External")));
            assertEquals(Set.of("Impl.java", "SubImpl.java"),
                    store.sourceFilesImplementingTypeIds(Set.of("p.Owner")));

            String ownerPlan = explain(store, "SELECT source_file FROM edges "
                    + "WHERE target_id='p.Owner' AND source_file IS NOT NULL UNION "
                    + "SELECT source_file FROM edges WHERE target_id>='p.Owner#' "
                    + "AND target_id<'p.Owner$' AND source_file IS NOT NULL");
            String externalPlan = explain(store, "SELECT DISTINCT source_file FROM edges "
                    + "WHERE is_external=1 AND external_target_fqn>='p.External#' "
                    + "AND external_target_fqn<'p.External$' AND source_file IS NOT NULL");
            assertTrue(ownerPlan.contains("idx_edges_target_id"), ownerPlan);
            assertTrue(externalPlan.contains("idx_edges_external_target_fqn"), externalPlan);
            assertFalse(ownerPlan.contains("SCAN edges"), ownerPlan);
            assertFalse(externalPlan.contains("SCAN edges"), externalPlan);
        }
    }

    private static String explain(SqliteStore store, String sql) throws Exception {
        StringBuilder out = new StringBuilder();
        try (Statement statement = store.connection().createStatement();
             ResultSet rows = statement.executeQuery("EXPLAIN QUERY PLAN " + sql)) {
            while (rows.next()) out.append(rows.getString("detail")).append('\n');
        }
        return out.toString();
    }

    private static Node node(String id, String sourceFile) {
        Node node = new Node();
        node.id = id;
        node.symbolId = id;
        node.label = id;
        node.kind = "CLASS";
        node.qualifiedName = id;
        node.sourceFile = sourceFile;
        node.scope = "MAIN";
        return node;
    }

    private static Edge edge(String source, String target, String sourceFile) {
        return edge(source, target, sourceFile, "REFERENCES");
    }

    private static Edge edge(String source, String target, String sourceFile, String relation) {
        Edge edge = new Edge();
        edge.sourceId = source;
        edge.targetId = target;
        edge.relation = relation;
        edge.confidence = "EXTRACTED";
        edge.sourceFile = sourceFile;
        return edge;
    }

    private static Edge externalEdge(String source, String target, String sourceFile) {
        Edge edge = edge(source, null, sourceFile);
        edge.isExternal = true;
        edge.externalTargetFqn = target;
        return edge;
    }

    private static Edge hierarchyEdge(String source, String target, String relation) {
        return edge(source, target, null, relation);
    }
}
