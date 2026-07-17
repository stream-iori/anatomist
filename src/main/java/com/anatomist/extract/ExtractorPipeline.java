package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.IndexTimings;
import com.anatomist.framework.JavaAstAnalyzer;
import com.anatomist.model.ExtractionResult;
import com.github.javaparser.ast.CompilationUnit;

import java.util.List;

public class ExtractorPipeline {

    private final ExtractionContext ctx;
    private final List<TimedExtractor> extractors;
    private final List<JavaAstAnalyzer> analyzers;
    private final IndexTimings timings;

    public ExtractorPipeline(ExtractionContext ctx) {
        this(ctx, List.of(), null);
    }

    public ExtractorPipeline(ExtractionContext ctx, List<JavaAstAnalyzer> analyzers) {
        this(ctx, analyzers, null);
    }

    public ExtractorPipeline(ExtractionContext ctx,
                             List<JavaAstAnalyzer> analyzers,
                             IndexTimings timings) {
        this.ctx = ctx;
        this.extractors = List.of(
                new TimedExtractor("full_extract_type", new TypeExtractor(ctx)),
                new TimedExtractor("full_extract_field", new FieldExtractor(ctx)),
                new TimedExtractor("full_extract_method", new MethodExtractor(ctx)),
                new TimedExtractor("full_extract_annotation", new AnnotationExtractor(ctx)),
                new TimedExtractor("full_extract_hierarchy", new HierarchyExtractor(ctx)),
                new TimedExtractor("full_extract_reference", new ReferenceExtractor(ctx)),
                new TimedExtractor("full_extract_call_graph", new CallGraphExtractor(ctx)),
                new TimedExtractor("full_extract_field_access", new FieldAccessExtractor(ctx))
        );
        this.analyzers = analyzers == null ? List.of() : List.copyOf(analyzers);
        this.timings = timings;
    }

    public void extractAll(CompilationUnit unit, ExtractionResult result) {
        ctx.enterFile(unit);
        int nodeStart = result.nodes.size();
        int edgeStart = result.edges.size();
        int annotationStart = result.annotations.size();
        for (TimedExtractor timed : extractors) {
            ctx.enterResolutionPhase(timed.phase());
            if (timings == null) {
                timed.extractor().extract(unit, result);
            } else {
                long started = timings.start();
                timed.extractor().extract(unit, result);
                timings.stop(timed.phase(), started);
            }
        }
        if (timings == null) {
            ctx.enterResolutionPhase("full_extract_java_analyzers");
            for (JavaAstAnalyzer analyzer : analyzers) analyzer.analyze(unit, result);
        } else {
            long started = timings.start();
            ctx.enterResolutionPhase("full_extract_java_analyzers");
            for (JavaAstAnalyzer analyzer : analyzers) analyzer.analyze(unit, result);
            timings.stop("full_extract_java_analyzers", started);
        }
        if (timings == null) {
            stampOrigin(result, nodeStart, edgeStart, annotationStart, SourceFiles.of(unit));
        } else {
            long started = timings.start();
            stampOrigin(result, nodeStart, edgeStart, annotationStart, SourceFiles.of(unit));
            timings.stop("full_extract_origin_stamp", started);
        }
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

    private record TimedExtractor(String phase, Extractor extractor) {}
}
