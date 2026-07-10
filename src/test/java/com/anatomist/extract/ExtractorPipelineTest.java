package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.JavaParserTestSupport;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.model.ExtractionResult;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractorPipelineTest {

    @Test
    void stampsSourceFileOnEveryJavaFact() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package p; class A { int value; void run() { value++; helper(); } void helper() {} }");
        cu.setData(TypeExtractor.SourceFileKey.KEY, "src/main/java/p/A.java");
        ExtractionContext ctx = new ExtractionContext(
                Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN");
        ExtractionResult result = new ExtractionResult();

        new ExtractorPipeline(ctx).extractAll(cu, result);

        assertFalse(result.nodes.isEmpty());
        assertFalse(result.edges.isEmpty());
        assertTrue(result.nodes.stream().allMatch(n -> n.sourceFile != null), "node origin missing");
        assertTrue(result.edges.stream().allMatch(e -> e.sourceFile != null), "edge origin missing");
        assertTrue(result.annotations.stream().allMatch(a -> a.sourceFile != null), "annotation origin missing");
    }
}
