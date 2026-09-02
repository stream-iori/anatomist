package com.anatomist.query;

import java.util.Set;

/** Versioned projection rules for the public, read-only JSON query contract. */
public final class QueryJsonContract {
    public static final int VERSION = 2;

    private static final Set<String> DERIVED_STATS = Set.of(
            "count", "found", "path_length",
            "members", "annotations", "framework", "source_status", "callees",
            "semantic_annotations", "related_docs",
            "extends_depth", "implements",
            "packages", "types", "methods", "package_deps");

    private QueryJsonContract() {}

    /** Remove values that can be derived directly from the result payload. */
    public static void compact(QueryEnvelope envelope) {
        DERIVED_STATS.forEach(envelope.stats::remove);
    }
}
