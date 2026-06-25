package com.anatomist.core;

import java.io.PrintStream;
import java.util.Map;
import java.util.Set;

public class IndexStatsPrinter {

    private static final Set<String> TYPE_KINDS = Set.of(
            "CLASS", "INTERFACE", "ENUM", "ANONYMOUS_CLASS", "RECORD");

    public static void print(IndexResult r, IndexConfig cfg, PrintStream out) {
        Map<String, Long> kindCounts = r.kindCounts();
        long types = kindCounts.entrySet().stream()
                .filter(e -> TYPE_KINDS.contains(e.getKey()))
                .mapToLong(Map.Entry::getValue).sum();
        long methods = kindCounts.getOrDefault("METHOD", 0L);
        long fields = kindCounts.getOrDefault("FIELD", 0L);
        long beans = kindCounts.getOrDefault("BEAN", 0L);

        Map<String, Long> relCounts = r.relationCounts();
        out.println("Indexed " + cfg.projectRoot());
        out.println("  Source paths: " + cfg.sourcePaths());
        out.println("  Classpath:    " + cfg.classpathEntries().size() + " entries");
        out.println("  Source files: " + cfg.sourceFiles().size());
        out.println("  Types:        " + types);
        out.println("  Methods:      " + methods);
        out.println("  Fields:       " + fields);
        out.println("  Annotations:  " + r.annotationCount());
        out.println("  CONTAINS:     " + relCounts.getOrDefault("CONTAINS", 0L));
        out.println("  INHERITS:     " + relCounts.getOrDefault("INHERITS", 0L));
        out.println("  IMPLEMENTS:   " + relCounts.getOrDefault("IMPLEMENTS", 0L));
        out.println("  OVERRIDES:    " + relCounts.getOrDefault("OVERRIDES", 0L));
        out.println("  REFERENCES:   " + relCounts.getOrDefault("REFERENCES", 0L));
        out.println("  CALLS:        " + relCounts.getOrDefault("CALLS", 0L));
        out.println("  READS:        " + relCounts.getOrDefault("READS", 0L));
        out.println("  WRITES:       " + relCounts.getOrDefault("WRITES", 0L));
        if (r.springXml()) {
            out.println("  Beans:        " + beans
                    + " (WIRES " + relCounts.getOrDefault("WIRES", 0L) + ")");
        }
        out.println("  Semantic annotations: " + r.semanticAnnotationCount());
        out.println("  Unresolved:   " + r.unresolvedCount());
        if (r.unresolvedCount() > 0 && !r.samplingEnabled()) {
            out.println("  Unresolved detail: enable -Danatomist.sampleUnresolved=true for categories");
        }
        out.println("  File cache:   " + r.fileCacheSize() + " entries");
        out.println("  Output:       " + cfg.dbPath());
        if (r.samplingEnabled() && r.unresolvedSamples() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> samples = r.unresolvedSamples();
            // Delegate to existing UnresolvedReporter — pass through
        }
        out.println("Done in " + r.elapsedMs() + "ms");
    }
}
