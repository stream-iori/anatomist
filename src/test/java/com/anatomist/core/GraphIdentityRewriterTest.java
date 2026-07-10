package com.anatomist.core;

import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphIdentityRewriterTest {

    @Test
    void duplicateCandidatesWithSameStorageKeyRemainBindable(@TempDir Path project) {
        Path sourceRoot = project.resolve("app/src/main/java");
        String sourceFile = "app/src/main/java/p/A.java";
        ExtractionResult result = new ExtractionResult();
        result.nodes.add(node("p.A", sourceFile));
        result.nodes.add(node("p.A", sourceFile));
        result.nodes.add(node("p.A#run()", sourceFile));
        result.nodes.add(node("p.A#run()", sourceFile));
        Edge contains = new Edge();
        contains.sourceId = "p.A";
        contains.targetId = "p.A#run()";
        contains.relation = GraphConstants.Relation.CONTAINS;
        contains.confidence = GraphConstants.Confidence.EXTRACTED;
        contains.isExternal = false;
        result.edges.add(contains);

        SourceIdentityResolver resolver = SourceIdentityResolver.fromRoots(project,
                List.of(new SourceRoot(sourceRoot, "app", SourceScope.MAIN)));
        GraphIdentityRewriter.rewrite(result, resolver, Set.of());

        assertEquals("app::MAIN::p.A", contains.sourceId);
        assertEquals("app::MAIN::p.A#run()", contains.targetId);
        assertEquals(sourceFile, contains.sourceFile);
    }

    private static Node node(String id, String sourceFile) {
        Node node = new Node();
        node.id = id;
        node.label = id;
        node.kind = id.contains("#") ? GraphConstants.Kind.METHOD : GraphConstants.Kind.CLASS;
        node.qualifiedName = id;
        node.sourceFile = sourceFile;
        return node;
    }
}
