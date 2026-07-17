package com.anatomist.flow;

import com.anatomist.core.JavaParserFactory;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowAnalyzerTest {

    @Test
    void buildsDefUseGuardsReturnAndExceptionSummaries(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src/main/java/p"));
        Path source = src.resolve("Sample.java");
        Files.writeString(source, """
                package p;
                class Sample {
                    String normalize(String input) {
                        String value = input;
                        if (value != null) {
                            value = value.trim();
                        } else {
                            value = "";
                        }
                        return value;
                    }
                    String wrapper(String input) {
                        return normalize(input);
                    }
                    void fail(String input) {
                        if (input == null) throw new IllegalArgumentException(input);
                    }
                }
                """);
        Path sourceRoot = tmp.resolve("src/main/java");
        JavaParserFactory parser = new JavaParserFactory(
                17, List.of(), List.of(sourceRoot), true);
        CompilationUnit unit = parser.parseFiles(List.of(source)).get(0);
        FlowResult result = new FlowResult();

        new FlowAnalyzer(tmp, List.of(sourceRoot), List.of(),
                TaintRules.load(tmp), false).analyze(unit, result);
        InterproceduralFlowLinker.link(result);

        assertFalse(result.nodes.isEmpty());
        assertTrue(result.edges.stream().anyMatch(edge -> "DEF_USE".equals(edge.relation())));
        assertTrue(result.edges.stream().anyMatch(edge -> "GUARD_TRUE".equals(edge.relation())));
        assertTrue(result.edges.stream().anyMatch(edge -> "GUARD_FALSE".equals(edge.relation())));
        assertTrue(result.edges.stream().anyMatch(edge -> "RETURN_FLOW".equals(edge.relation())));
        assertTrue(result.edges.stream().anyMatch(edge -> "EXCEPTION_FLOW".equals(edge.relation())));
        assertTrue(result.summaries.stream().anyMatch(summary ->
                "arg:0".equals(summary.inputSlot()) && "return".equals(summary.outputSlot())));
        assertTrue(result.edges.stream().anyMatch(edge -> "CALL_RETURN".equals(edge.relation())));
    }
}
