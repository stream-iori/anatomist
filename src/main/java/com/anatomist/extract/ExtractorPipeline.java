package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.IndexTimings;
import com.anatomist.framework.JavaUnitAnalyzer;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.FactOrigin;
import com.anatomist.model.ProducerIds;
import com.github.javaparser.ast.CompilationUnit;

import java.util.List;

public class ExtractorPipeline {

    private final ExtractionContext ctx;
    private final List<TimedExtractor> extractors;
    private final List<JavaUnitAnalyzer> analyzers;
    private final IndexTimings timings;

    public ExtractorPipeline(ExtractionContext ctx) {
        this(ctx, List.of(), null);
    }

    public ExtractorPipeline(ExtractionContext ctx, List<? extends JavaUnitAnalyzer> analyzers) {
        this(ctx, analyzers, null);
    }

    public ExtractorPipeline(ExtractionContext ctx,
                             List<? extends JavaUnitAnalyzer> analyzers,
                             IndexTimings timings) {
        this.ctx = ctx;
        this.extractors = List.of(
                new TimedExtractor("full_extract_type", new TypeExtractor(ctx)),
                new TimedExtractor("full_extract_field", new FieldExtractor(ctx)),
                new TimedExtractor("full_extract_method", new MethodExtractor(ctx)),
                new TimedExtractor("full_extract_declaration", new DeclarationExtractor(ctx)),
                new TimedExtractor("full_extract_annotation", new AnnotationExtractor(ctx)),
                new TimedExtractor("full_extract_hierarchy", new HierarchyExtractor(ctx)),
                new TimedExtractor("full_extract_reference", new ReferenceExtractor(ctx)),
                new TimedExtractor("full_extract_call_graph", new CallGraphExtractor(ctx)),
                new TimedExtractor("full_extract_reflection", new ReflectionExtractor(ctx)),
                new TimedExtractor("full_extract_field_access", new FieldAccessExtractor(ctx))
        );
        this.analyzers = analyzers == null ? List.of() : List.copyOf(analyzers);
        this.timings = timings;
    }

    public void extractAll(CompilationUnit unit, ExtractionResult result) {
        ctx.enterFile(unit);
        String sourceFile = SourceFiles.of(unit);
        FactOrigin.Cursor coreStart = FactOrigin.cursor(result);
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
        FactOrigin.stamp(result, coreStart, sourceFile, ProducerIds.JAVA_CORE);
        if (timings == null) {
            ctx.enterResolutionPhase("full_extract_java_analyzers");
            for (JavaUnitAnalyzer analyzer : analyzers) analyze(analyzer, unit, result, sourceFile);
        } else {
            long started = timings.start();
            ctx.enterResolutionPhase("full_extract_java_analyzers");
            for (JavaUnitAnalyzer analyzer : analyzers) analyze(analyzer, unit, result, sourceFile);
            timings.stop("full_extract_java_analyzers", started);
        }
    }

    private static void analyze(JavaUnitAnalyzer analyzer, CompilationUnit unit,
                                ExtractionResult result, String sourceFile) {
        FactOrigin.Cursor start = FactOrigin.cursor(result);
        analyzer.analyze(unit, result);
        FactOrigin.stamp(result, start, sourceFile, analyzer.producerId());
    }

    private record TimedExtractor(String phase, Extractor extractor) {}
}
