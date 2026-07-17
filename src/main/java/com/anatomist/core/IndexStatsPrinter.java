package com.anatomist.core;

import com.anatomist.model.GraphConstants;

import java.io.PrintStream;
import java.util.Map;

public class IndexStatsPrinter {

    public static void print(IndexResult r, IndexConfig cfg, PrintStream out) {
        Map<String, Long> kindCounts = r.kindCounts();
        long types = kindCounts.entrySet().stream()
                .filter(e -> GraphConstants.INDEX_SUMMARY_TYPE_KINDS.contains(e.getKey()))
                .mapToLong(Map.Entry::getValue).sum();
        long methods = kindCounts.getOrDefault(GraphConstants.Kind.METHOD, 0L);
        long fields = kindCounts.getOrDefault(GraphConstants.Kind.FIELD, 0L);
        long beans = kindCounts.getOrDefault(GraphConstants.Kind.BEAN, 0L);

        Map<String, Long> relCounts = r.relationCounts();
        out.println("Indexed " + cfg.projectRoot());
        out.println("  Source paths: " + cfg.sourcePaths());
        out.println("  Classpath:    " + cfg.classpathEntries().size() + " entries");
        ParseInventory parse = r.parseInventory() == null
                ? ParseInventory.complete(cfg.sourceFiles().size()) : r.parseInventory();
        out.println("  Source files: " + parse.scannedFiles()
                + " (parsed " + parse.parsedFiles() + ", failed " + parse.failedFiles() + ")");
        out.println("  Types:        " + types);
        out.println("  Methods:      " + methods);
        out.println("  Fields:       " + fields);
        out.println("  Annotations:  " + r.annotationCount());
        out.println("  CONTAINS:     " + relCounts.getOrDefault(GraphConstants.Relation.CONTAINS, 0L));
        out.println("  INHERITS:     " + relCounts.getOrDefault(GraphConstants.Relation.INHERITS, 0L));
        out.println("  IMPLEMENTS:   " + relCounts.getOrDefault(GraphConstants.Relation.IMPLEMENTS, 0L));
        out.println("  OVERRIDES:    " + relCounts.getOrDefault(GraphConstants.Relation.OVERRIDES, 0L));
        out.println("  REFERENCES:   " + relCounts.getOrDefault(GraphConstants.Relation.REFERENCES, 0L));
        out.println("  CALLS:        " + relCounts.getOrDefault(GraphConstants.Relation.CALLS, 0L));
        out.println("  READS:        " + relCounts.getOrDefault(GraphConstants.Relation.READS, 0L));
        out.println("  WRITES:       " + relCounts.getOrDefault(GraphConstants.Relation.WRITES, 0L));
        if (r.springXml()) {
            out.println("  Beans:        " + beans
                    + " (WIRES " + relCounts.getOrDefault(GraphConstants.Relation.WIRES, 0L) + ")");
        }
        out.println("  Semantic annotations: " + r.semanticAnnotationCount());
        out.println("  Unresolved:   " + r.unresolvedCount());
        if (r.unresolvedCount() > 0 && !r.samplingEnabled()) {
            out.println("  Unresolved detail: enable -Danatomist.sampleUnresolved=true for categories");
        }
        out.println("  File cache:   " + r.fileCacheSize() + " entries");
        if (cfg.dataflow()) {
            out.println("  Flow facts:   " + r.flowNodes() + " nodes, "
                    + r.flowEdges() + " edges, " + r.flowSummaries() + " summaries");
        }
        out.println("  Output:       " + cfg.dbPath());
        if (r.samplingEnabled() && r.unresolvedSamples() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> samples = r.unresolvedSamples();
            // Delegate to existing UnresolvedReporter — pass through
        }
        out.println("Done in " + r.elapsedMs() + "ms");
    }
}
