package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.framework.JavaAstAnalyzer;
import com.anatomist.model.ExtractionResult;
import com.github.javaparser.ast.CompilationUnit;

import java.util.List;

public class ExtractorPipeline {

    private final List<Extractor> extractors;
    private final List<JavaAstAnalyzer> analyzers;

    public ExtractorPipeline(ExtractionContext ctx) {
        this(ctx, List.of());
    }

    public ExtractorPipeline(ExtractionContext ctx, List<JavaAstAnalyzer> analyzers) {
        this.extractors = List.of(
                new TypeExtractor(ctx),
                new FieldExtractor(ctx),
                new MethodExtractor(ctx),
                new AnnotationExtractor(ctx),
                new HierarchyExtractor(ctx),
                new ReferenceExtractor(ctx),
                new CallGraphExtractor(ctx),
                new FieldAccessExtractor(ctx)
        );
        this.analyzers = analyzers == null ? List.of() : List.copyOf(analyzers);
    }

    public void extractAll(CompilationUnit unit, ExtractionResult result) {
        int nodeStart = result.nodes.size();
        int edgeStart = result.edges.size();
        int annotationStart = result.annotations.size();
        for (Extractor e : extractors) {
            e.extract(unit, result);
        }
        for (JavaAstAnalyzer analyzer : analyzers) {
            analyzer.analyze(unit, result);
        }
        stampOrigin(result, nodeStart, edgeStart, annotationStart, SourceFiles.of(unit));
    }

    private static void stampOrigin(ExtractionResult result,
                                    int nodeStart,
                                    int edgeStart,
                                    int annotationStart,
                                    String sourceFile) {
        if (sourceFile == null) return;
        for (int i = nodeStart; i < result.nodes.size(); i++) {
            if (result.nodes.get(i).sourceFile == null) result.nodes.get(i).sourceFile = sourceFile;
        }
        for (int i = edgeStart; i < result.edges.size(); i++) {
            if (result.edges.get(i).sourceFile == null) result.edges.get(i).sourceFile = sourceFile;
        }
        for (int i = annotationStart; i < result.annotations.size(); i++) {
            if (result.annotations.get(i).sourceFile == null) {
                result.annotations.get(i).sourceFile = sourceFile;
            }
        }
    }
}
