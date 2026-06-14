package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.model.ExtractionResult;
import com.github.javaparser.ast.CompilationUnit;

import java.util.List;

public class ExtractorPipeline {

    private final List<Extractor> extractors;

    public ExtractorPipeline(ExtractionContext ctx) {
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
    }

    public void extractAll(CompilationUnit unit, ExtractionResult result) {
        for (Extractor e : extractors) {
            e.extract(unit, result);
        }
    }
}
