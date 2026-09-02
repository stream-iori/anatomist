package com.anatomist.framework.spring;

import com.anatomist.framework.AnalysisContext;
import com.anatomist.framework.PreparedExtensions;

/** Single compile-time registration point used by full, incremental and watch indexing. */
public final class BuiltInExtensions {
    private BuiltInExtensions() {}

    public static PreparedExtensions prepare(AnalysisContext context) {
        return PreparedExtensions.prepare(SpringAnalyzers.registry(context));
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
