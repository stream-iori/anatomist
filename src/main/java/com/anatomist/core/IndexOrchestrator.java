package com.anatomist.core;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.logging.AnatomistLog;
import com.anatomist.extract.ExtractorPipeline;
import com.anatomist.extract.TypeExtractor;
import com.anatomist.framework.AnalysisContext;
import com.anatomist.framework.AnalyzerRegistry;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;
import com.anatomist.store.FileCacheService;
import com.anatomist.store.SqliteStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class IndexOrchestrator {

    private final IndexConfig cfg;
    private final JavaParserFactory factory;

    public IndexOrchestrator(IndexConfig cfg, JavaParserFactory factory) {
        this.cfg = cfg;
        this.factory = factory;
    }

    public IndexResult run(SqliteStore store) throws Exception {
        long started = System.currentTimeMillis();

        if (!store.schemaExists()) {
            store.initSchema();
        } else {
            store.clearAllData();
        }

        NodeIdGenerator idGen = new NodeIdGenerator();
        ExtractionContext ctx = new ExtractionContext(
                cfg.projectRoot(), cfg.sourcePaths(), idGen, null, "MAIN", cfg.config());
        AnalysisContext analysisContext = new AnalysisContext(
                cfg.projectRoot(), cfg.sourcePaths(), ctx, cfg.config(), cfg.springXml());
        ExtractorPipeline pipeline = new ExtractorPipeline(
                ctx, AnalyzerRegistry.javaAstAnalyzers(analysisContext));

        ExtractionResult result = new ExtractionResult();
        result.setNodeFlusher(store::writeNodes);

        Progress progress = new Progress(cfg.sourceFiles().size());
        factory.parseAll((filePath, cu) -> {
            String relative = filePath == null ? null : relativize(cfg.projectRoot(), filePath);
            if (relative != null) cu.setData(TypeExtractor.SourceFileKey.KEY, relative);
            pipeline.extractAll(cu, result);
            result.flushNodesIfNeeded();
            progress.tick();
        });
        progress.done();

        result.flushRemainingNodes();

        for (var analyzer : AnalyzerRegistry.projectAnalyzers()) {
            if (analyzer.enabled(analysisContext)) {
                analyzer.analyze(analysisContext, result);
                result.flushRemainingNodes();
            }
        }

        int wired = new WiringResolver().apply(result);
        if (wired > 0) {
            AnatomistLog.warn("added " + wired + " DI-informed wiring edges "
                    + "for " + cfg.projectRoot());
        }

        GraphPostProcessor.Summary post = new GraphPostProcessor().process(result);
        int rebound = post.reboundExternalTargets();
        if (rebound > 0) {
            AnatomistLog.warn("rebound " + rebound + " external edges to internal nodes "
                    + "for " + cfg.projectRoot());
        }

        int dropped = post.droppedDanglingFacts();
        if (dropped > 0) {
            AnatomistLog.warn("dropped " + dropped + " edges with dangling internal target "
                    + "(extractor gaps) for " + cfg.projectRoot());
        }

        store.writeEdgesBatched(result.edges, ExtractionResult.FLUSH_THRESHOLD);
        store.writeAnnotationsBatched(result.annotations, result.semanticAnnotations,
                ExtractionResult.FLUSH_THRESHOLD);

        List<Path> cachedFiles = cfg.sourceFiles();
        List<Path> xmlFiles = Collections.emptyList();
        if (cfg.springXml()) {
            xmlFiles = new ProjectScanner().scanSpringXml(cfg.projectRoot());
        }
        if (!xmlFiles.isEmpty()) {
            cachedFiles = new ArrayList<>(cfg.sourceFiles());
            cachedFiles.addAll(xmlFiles);
        }
        populateFileCache(store, cfg.projectRoot(), cachedFiles);
        ProjectMetadata.write(store, cfg, dropped, rebound, wired);
        store.refreshFileDependencies();

        store.runAnalyze();

        long elapsed = System.currentTimeMillis() - started;

        Map<String, Object> unresolvedSamples = ctx.samplingEnabled()
                ? toSamplesMap(ctx, projectPackagesOf(result))
                : null;

        return new IndexResult(
                store.queryKindCounts(),
                store.queryRelationCounts(),
                store.queryAnnotationCount(),
                store.querySemanticAnnotationCount(),
                store.readFileCache().size(),
                ctx.unresolvedCount(),
                dropped,
                elapsed,
                cfg.springXml(),
                unresolvedSamples,
                ctx.samplingEnabled()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toSamplesMap(ExtractionContext ctx, Set<String> projectPackages) {
        Map<String, Object> map = new HashMap<>();
        map.put("samples", ctx.unresolvedSamples());
        map.put("projectPackages", projectPackages);
        map.put("unresolvedCount", Long.valueOf(ctx.unresolvedCount()));
        return map;
    }

    private static void populateFileCache(SqliteStore store, Path projectRoot, List<Path> sourceFiles) {
        String now = Instant.now().toString();
        List<com.anatomist.model.FileCacheEntry> entries = FileCacheService.buildEntries(
                projectRoot, sourceFiles, store.sourceFileStats(), now);
        if (!entries.isEmpty()) store.updateFileCache(entries);
    }

    static String classpathFingerprint(List<Path> classpathEntries, String override) {
        if (override != null && !override.isEmpty()) return override;
        if (classpathEntries == null || classpathEntries.isEmpty()) return "";
        List<String> sorted = classpathEntries.stream()
                .map(Path::toString).sorted().collect(java.util.stream.Collectors.toList());
        return String.join(java.io.File.pathSeparator, sorted);
    }

    static String relativize(Path root, Path file) {
        try {
            return root.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    private static Set<String> projectPackagesOf(ExtractionResult result) {
        Set<String> projectPackages = new HashSet<>();
        for (Node n : result.nodes) {
            if (!isType(n.kind) || n.qualifiedName == null) continue;
            int dot = n.qualifiedName.lastIndexOf('.');
            if (dot > 0) projectPackages.add(n.qualifiedName.substring(0, dot));
        }
        return projectPackages;
    }

    private static boolean isType(String kind) {
        return GraphConstants.INDEX_SUMMARY_TYPE_KINDS.contains(kind);
    }

    private static final class Progress {
        private final int total;
        private final boolean tty;
        private int done;
        private long lastEmit;

        Progress(int total) {
            this.total = total;
            this.tty = System.console() != null;
        }

        void tick() {
            done++;
            if (tty) {
                long now = System.currentTimeMillis();
                if (now - lastEmit >= 100) {
                    System.err.print("\rParsing & extracting: " + done + "/" + total + " files");
                    System.err.flush();
                    lastEmit = now;
                }
            } else if (done % 200 == 0) {
                System.err.println("Parsing & extracting: " + done + "/" + total + " files");
            }
        }

        void done() {
            if (tty) {
                System.err.print("\rParsing & extracting: " + done + "/" + total + " files\n");
                System.err.flush();
            } else {
                System.err.println("Parsing & extracting: " + done + "/" + total + " files (done)");
            }
        }
    }
}
