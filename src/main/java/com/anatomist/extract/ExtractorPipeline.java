package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.IndexTimings;
import com.anatomist.framework.JavaUnitAnalyzer;
import com.anatomist.framework.ExtensionReport;
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
    private final ExtensionReport extensionReport;

    public ExtractorPipeline(ExtractionContext ctx) {
        this(ctx, List.of(), null, null);
    }

    public ExtractorPipeline(ExtractionContext ctx, List<? extends JavaUnitAnalyzer> analyzers) {
        this(ctx, analyzers, null, null);
    }

    public ExtractorPipeline(ExtractionContext ctx,
                             List<? extends JavaUnitAnalyzer> analyzers,
                             IndexTimings timings) {
        this(ctx, analyzers, timings, null);
    }

    public ExtractorPipeline(ExtractionContext ctx,
                             List<? extends JavaUnitAnalyzer> analyzers,
                             IndexTimings timings,
                             ExtensionReport extensionReport) {
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
        this.extensionReport = extensionReport;
    }

    public void extractAll(CompilationUnit unit, ExtractionResult result) {
        ctx.enterFile(unit);
        String sourceFile = SourceFiles.of(unit);
        if (extensionReport != null) extensionReport.consume(unit, sourceFile);
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
        assignExplicitOwners(result, coreStart);
        FactOrigin.stampDefault(result, coreStart, sourceFile, ProducerIds.JAVA_CORE);
        if (timings == null) {
            ctx.enterResolutionPhase("full_extract_java_analyzers");
            for (JavaUnitAnalyzer analyzer : analyzers) analyzeIsolated(analyzer, unit, result, sourceFile);
        } else {
            long started = timings.start();
            ctx.enterResolutionPhase("full_extract_java_analyzers");
            for (JavaUnitAnalyzer analyzer : analyzers) analyzeIsolated(analyzer, unit, result, sourceFile);
            timings.stop("full_extract_java_analyzers", started);
        }
    }

    private static void analyze(JavaUnitAnalyzer analyzer, CompilationUnit unit,
                                ExtractionResult result, String sourceFile) {
        ExtractionResult isolated = new ExtractionResult();
        analyzer.analyze(unit, isolated);
        FactOrigin.stamp(isolated, FactOrigin.beginning(),
                sourceFile, analyzer.producerId());
        result.addFacts(isolated);
    }

    private void analyzeIsolated(JavaUnitAnalyzer analyzer, CompilationUnit unit,
                                 ExtractionResult result, String sourceFile) {
        long started = timings == null ? 0L : timings.start();
        try {
            analyze(analyzer, unit, result, sourceFile);
        } catch (RuntimeException failure) {
            if (extensionReport != null) extensionReport.diagnostic(new com.anatomist.core.IndexDiagnostic(
                    "warning", "EXTENSION_JAVA_ANALYZER_FAILED", "EXTENSION_FACT_EMIT",
                    sourceFile, ctx.module(), ctx.scope(), analyzer.id(), 1,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage()));
        } finally {
            if (timings != null) timings.stop("extension_fact_emit", started);
        }
    }

    private static void assignExplicitOwners(ExtractionResult result, FactOrigin.Cursor start) {
        java.util.Map<String, String> generated = new java.util.HashMap<>();
        for (int i = start.nodes(); i < result.nodes.size(); i++) {
            var node = result.nodes.get(i);
            if (node.producerId != null) generated.put(node.id, node.producerId);
        }
        for (int i = start.edges(); i < result.edges.size(); i++) {
            var edge = result.edges.get(i);
            String sourceOwner = generated.get(edge.sourceId);
            String targetOwner = generated.get(edge.targetId);
            if (sourceOwner != null) edge.producerId = sourceOwner;
            else if (com.anatomist.model.GraphConstants.Relation.CONTAINS.equals(edge.relation)
                    && targetOwner != null) edge.producerId = targetOwner;
        }
        for (int i = start.annotations(); i < result.annotations.size(); i++) {
            var annotation = result.annotations.get(i);
            String owner = generated.get(annotation.nodeId);
            if (owner != null) annotation.producerId = owner;
        }
    }

    private record TimedExtractor(String phase, Extractor extractor) {}
}
