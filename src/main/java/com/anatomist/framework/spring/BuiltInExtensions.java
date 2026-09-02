package com.anatomist.framework.spring;

import com.anatomist.framework.AnalysisContext;
import com.anatomist.framework.AnalyzerRegistry;
import com.anatomist.framework.PreparedExtensions;
import com.anatomist.framework.lombok.LombokAstModelExtension;
import com.anatomist.framework.lombok.LombokMode;

import java.util.ArrayList;

/** Single compile-time registration point used by full and incremental indexing. */
public final class BuiltInExtensions {
    private BuiltInExtensions() {}

    public static PreparedExtensions prepare(AnalysisContext context) {
        AnalyzerRegistry spring = SpringAnalyzers.registry(context);
        var astExtensions = new ArrayList<>(spring.astModelExtensions());
        if (context != null && context.config() != null
                && context.config().lombokMode() == LombokMode.AST) {
            astExtensions.add(new LombokAstModelExtension(
                    context.projectRoot(), context.config().lombokStrict()));
        }
        return PreparedExtensions.prepare(new AnalyzerRegistry(
                astExtensions,
                spring.javaUnitAnalyzers(),
                spring.projectResourceProviders(),
                spring.projectResourceAnalyzers()));
    }

    /** Descriptor-only registry used while constructing the parser runtime. */
    public static PreparedExtensions current() {
        return PreparedExtensions.prepare(SpringAnalyzers.registry(null));
    }

    /** Fingerprint can be checked before parser/extraction contexts are allocated. */
    public static String currentFingerprint() {
        return current().fingerprint();
    }
}
