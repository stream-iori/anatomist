package com.anatomist.framework;

import com.anatomist.store.FileCacheService;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Processor;
import com.github.javaparser.ast.CompilationUnit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Validated immutable extension set prepared once per indexing runtime. */
public record PreparedExtensions(AnalyzerRegistry registry, String fingerprint) {
    public static final String META_KEY = "extension_fingerprint";

    public static PreparedExtensions prepare(AnalyzerRegistry registry) {
        AnalyzerRegistry effective = registry == null
                ? new AnalyzerRegistry(List.of(), List.of(), List.of()) : registry;
        Set<String> ids = new HashSet<>();
        List<ExtensionPoint> all = new ArrayList<>();
        all.addAll(effective.astModelExtensions());
        all.addAll(effective.javaUnitAnalyzers());
        all.addAll(effective.projectResourceAnalyzers());
        StringBuilder canonical = new StringBuilder("anatomist-extensions-v1\n");
        for (ExtensionPoint extension : all) {
            if (extension.id() == null || extension.id().isBlank()) {
                throw new IllegalArgumentException("extension id must not be blank");
            }
            if (!ids.add(extension.id())) {
                throw new IllegalArgumentException("duplicate extension id: " + extension.id());
            }
            canonical.append(extension.getClass().getName()).append('|')
                    .append(extension.id()).append('|').append(extension.version()).append('|')
                    .append(extension.producerId()).append('\n');
        }
        return new PreparedExtensions(effective,
                FileCacheService.sha256OfString(canonical.toString()));
    }

    public List<Supplier<Processor>> processorSuppliers() {
        if (registry.astModelExtensions().isEmpty()) return List.of();
        return List.of(() -> new ExtensionProcessor(registry.astModelExtensions()));
    }

    static final class ExtensionProcessor extends Processor {
        private final List<AstModelExtension> extensions;

        ExtensionProcessor(List<AstModelExtension> extensions) {
            this.extensions = extensions;
        }

        @Override
        public void postProcess(ParseResult<? extends com.github.javaparser.ast.Node> result,
                                ParserConfiguration configuration) {
            if (result.getResult().orElse(null) instanceof CompilationUnit unit) {
                for (AstModelExtension extension : extensions) extension.augment(unit);
            }
        }
    }
}
