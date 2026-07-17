package com.anatomist.core;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.logging.AnatomistLog;
import com.anatomist.extract.ExtractorPipeline;
import com.anatomist.extract.TypeExtractor;
import com.anatomist.framework.AnalysisContext;
import com.anatomist.framework.AnalyzerRegistry;
import com.anatomist.framework.spring.SpringXmlAnalyzer;
import com.anatomist.flow.FlowAnalyzer;
import com.anatomist.flow.FlowPersistence;
import com.anatomist.flow.FlowResult;
import com.anatomist.flow.InterproceduralFlowLinker;
import com.anatomist.flow.TaintRules;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;
import com.anatomist.store.FileCacheService;
import com.anatomist.store.SqliteStore;
import com.anatomist.store.StagedGraphStore;
import com.anatomist.semantic.SemanticPostProcessor;

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

    private static final int STAGE_FILE_BATCH = 32;
    private static final int STAGE_FACT_BATCH = 10_000;

    private final IndexConfig cfg;
    private final JavaParserFactory factory;

    public IndexOrchestrator(IndexConfig cfg, JavaParserFactory factory) {
        this.cfg = cfg;
        this.factory = factory;
    }

    public IndexResult run(SqliteStore store) throws Exception {
        return run(store, null);
    }

    public IndexResult run(SqliteStore store, IndexTimings timings) throws Exception {
        long started = System.currentTimeMillis();
        factory.setTimings(timings);

        long phaseStarted = startTiming(timings);
        if (!store.schemaExists()) store.initSchema();
        stopTiming(timings, "full_schema", phaseStarted);

        NodeIdGenerator idGen = new NodeIdGenerator();
        ExtractionContext ctx = new ExtractionContext(
                cfg.projectRoot(), cfg.sourcePaths(), idGen, null, "MAIN", cfg.config());
        AnalysisContext analysisContext = new AnalysisContext(
                cfg.projectRoot(), cfg.sourcePaths(), ctx, cfg.config(), cfg.springXml());
        ExtractorPipeline pipeline = new ExtractorPipeline(
                ctx, AnalyzerRegistry.javaAstAnalyzers(analysisContext), timings);

        SourceIdentityResolver identityResolver = cfg.sourceRoots() == null || cfg.sourceRoots().isEmpty()
                ? new SourceIdentityResolver(cfg.projectRoot(), cfg.sourcePaths())
                : SourceIdentityResolver.fromRoots(cfg.projectRoot(), cfg.sourceRoots());
        ExtractionResult result = new ExtractionResult();
        FlowResult flowResult = new FlowResult();
        TaintRules taintRules = TaintRules.load(cfg.projectRoot());
        FlowAnalyzer flowAnalyzer = cfg.dataflow()
                ? new FlowAnalyzer(cfg.projectRoot(), cfg.sourcePaths(), cfg.sourceRoots(),
                taintRules, cfg.implicitTaint())
                : null;
        Map<String, String> contractHashes = new java.util.LinkedHashMap<>();
        List<Path> parsedJavaFiles = new ArrayList<>();
        StagedGraphStore.PromotionStats promotion;
        List<Path> xmlFiles = Collections.emptyList();
        ParseInventory parseInventory;

        try (StagedGraphStore staging = new StagedGraphStore(cfg.dbPath(), identityResolver)) {
            Progress progress = new Progress(cfg.sourceFiles().size());
            long parseExtractStarted = startTiming(timings);
            long[] extractNanos = {0L};
            long[] stageNanos = {0L};
            int[] batchFiles = {0};
            parseInventory = factory.parseInventory(cfg.sourceFiles(), (filePath, cu) -> {
                long extractStarted = startTiming(timings);
                String relative = filePath == null ? null : relativize(cfg.projectRoot(), filePath);
                if (relative != null) cu.setData(TypeExtractor.SourceFileKey.KEY, relative);
                if (relative != null) contractHashes.put(relative, JavaContractFingerprint.of(cu));
                if (filePath != null) parsedJavaFiles.add(filePath.toAbsolutePath().normalize());
                pipeline.extractAll(cu, result);
                if (flowAnalyzer != null) {
                    try {
                        flowAnalyzer.analyze(cu, flowResult);
                    } catch (RuntimeException failure) {
                        flowResult.diagnostics.add(new IndexDiagnostic(
                                "warning", "FLOW_ANALYSIS_FAILED", "FLOW",
                                relative, null, null, null, 1, failure.getMessage()));
                    }
                }
                if (timings != null) extractNanos[0] += System.nanoTime() - extractStarted;
                batchFiles[0]++;
                if (batchFiles[0] >= STAGE_FILE_BATCH || result.factCount() >= STAGE_FACT_BATCH) {
                    stageNanos[0] += flushRawBatch(staging, result, timings);
                    batchFiles[0] = 0;
                }
                progress.tick();
            });
            parseInventory = relativeInventory(parseInventory, cfg.projectRoot());
            for (int i = 0; i < parseInventory.failedFiles(); i++) progress.tick();
            stageNanos[0] += flushRawBatch(staging, result, timings);
            progress.done();
            if (timings != null) {
                long parseExtractNanos = System.nanoTime() - parseExtractStarted;
                timings.addNanos("full_parse_extract", parseExtractNanos);
                timings.addNanos("full_extract", extractNanos[0]);
                timings.addNanos("full_parse", Math.max(0L,
                        parseExtractNanos - extractNanos[0] - stageNanos[0]));
            }

            if (cfg.strictHealth() && !parseInventory.complete()) {
                throw new StrictHealthException(parseInventory);
            }

            phaseStarted = startTiming(timings);
            if (cfg.springXml()) {
                xmlFiles = new ProjectScanner().scanSpringXml(cfg.projectRoot());
                if (!xmlFiles.isEmpty()) {
                    ExtractionResult xmlResult = new ExtractionResult();
                    SpringXmlAnalyzer.extractXmlBeans(cfg.projectRoot(), xmlFiles,
                            staging.allSymbolIds(), staging.rawBeanTargets(), xmlResult);
                    staging.writeRawBatch(xmlResult);
                    xmlResult.clearFacts();
                }
            }
            stopTiming(timings, "full_project_analyzers", phaseStarted);

            phaseStarted = startTiming(timings);
            staging.finalizeRawFacts();
            stopTiming(timings, "full_stage_resolve", phaseStarted);

            phaseStarted = startTiming(timings);
            promotion = staging.promoteFull(store);
            stopTiming(timings, "full_stage_promote", phaseStarted);
            if (timings != null) {
                // Compatibility aliases retained for existing timing consumers.
                timings.addNanos("full_write_nodes", 0L);
                timings.addNanos("full_write_edges", 0L);
                timings.addNanos("full_write_annotations", 0L);
            }
        }

        int wired = promotion.wiredEdges();
        if (wired > 0) {
            AnatomistLog.warn("added " + wired + " DI-informed wiring edges "
                    + "for " + cfg.projectRoot());
        }

        int rebound = promotion.reboundExternalTargets();
        if (rebound > 0) {
            AnatomistLog.warn("rebound " + rebound + " external edges to internal nodes "
                    + "for " + cfg.projectRoot());
        }

        int dropped = promotion.droppedDanglingFacts();
        if (dropped > 0) {
            AnatomistLog.warn("dropped " + dropped + " edges with dangling internal target "
                    + "(extractor gaps) for " + cfg.projectRoot());
        }

        phaseStarted = startTiming(timings);
        if (cfg.dataflow()) {
            InterproceduralFlowLinker.link(flowResult);
            FlowPersistence.replaceAll(store, flowResult);
            FlowPersistence.relinkInterprocedural(store);
        } else {
            FlowPersistence.replaceAll(store, new FlowResult());
        }
        stopTiming(timings, "full_flow_write", phaseStarted);

        List<Path> cachedFiles = parsedJavaFiles;
        if (!xmlFiles.isEmpty()) {
            cachedFiles = new ArrayList<>(parsedJavaFiles);
            cachedFiles.addAll(xmlFiles);
        }
        phaseStarted = startTiming(timings);
        populateFileCache(store, cfg.projectRoot(), cachedFiles, contractHashes);
        stopTiming(timings, "full_file_cache", phaseStarted);
        phaseStarted = startTiming(timings);
        ProjectMetadata.write(store, cfg, dropped, rebound, wired, timings);
        stopTiming(timings, "full_metadata", phaseStarted);
        phaseStarted = startTiming(timings);
        store.refreshFileDependencies();
        stopTiming(timings, "full_file_dependencies", phaseStarted);

        phaseStarted = startTiming(timings);
        store.runAnalyze();
        stopTiming(timings, "full_analyze", phaseStarted);

        long elapsed = System.currentTimeMillis() - started;

        phaseStarted = startTiming(timings);
        Map<String, Object> unresolvedSamples = ctx.samplingEnabled()
                ? toSamplesMap(ctx, projectPackagesOf(store))
                : null;

        ResolutionSummary resolution = ctx.resolutionSummary(cfg.noClasspath());
        List<IndexDiagnostic> detailedDiagnostics = new ArrayList<>();
        if (cfg.javaVersionDetection() != null) {
            detailedDiagnostics.addAll(cfg.javaVersionDetection().diagnostics());
        }
        detailedDiagnostics.addAll(resolution.diagnostics());
        detailedDiagnostics.addAll(taintRules.diagnostics());
        detailedDiagnostics.addAll(flowResult.diagnostics);
        IndexResult indexResult = new IndexResult(
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
                ctx.samplingEnabled(),
                parseInventory,
                detailedDiagnostics,
                flowResult.nodes.size(),
                flowResult.edges.size(),
                flowResult.summaries.size()
        );
        store.replaceIndexDiagnostics(IndexHealthService.fromResult(indexResult).diagnostics());
        stopTiming(timings, "full_stats_health", phaseStarted);
        return indexResult;
    }

    private static long startTiming(IndexTimings timings) {
        return timings == null ? 0L : timings.start();
    }

    private static void stopTiming(IndexTimings timings, String phase, long started) {
        if (timings != null) timings.stop(phase, started);
    }

    private static long flushRawBatch(StagedGraphStore staging,
                                      ExtractionResult result,
                                      IndexTimings timings) {
        if (result.factCount() == 0) return 0L;
        long started = startTiming(timings);
        new SemanticPostProcessor().process(result);
        staging.writeRawBatch(result);
        result.clearFacts();
        long elapsed = timings == null ? 0L : System.nanoTime() - started;
        if (timings != null) timings.addNanos("full_stage_write", elapsed);
        return elapsed;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toSamplesMap(ExtractionContext ctx, Set<String> projectPackages) {
        Map<String, Object> map = new HashMap<>();
        map.put("samples", ctx.unresolvedSamples());
        map.put("projectPackages", projectPackages);
        map.put("unresolvedCount", Long.valueOf(ctx.unresolvedCount()));
        return map;
    }

    private static void populateFileCache(SqliteStore store, Path projectRoot, List<Path> sourceFiles,
                                          Map<String, String> contractHashes) {
        String now = Instant.now().toString();
        List<com.anatomist.model.FileCacheEntry> entries = FileCacheService.buildEntries(
                projectRoot, sourceFiles, store.sourceFileStats(), now, contractHashes);
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

    private static ParseInventory relativeInventory(ParseInventory inventory, Path root) {
        Map<Path, List<String>> failures = new java.util.LinkedHashMap<>();
        inventory.failures().forEach((path, problems) ->
                failures.put(Path.of(relativize(root, path)), problems));
        return new ParseInventory(inventory.scannedFiles(), inventory.attemptedFiles(),
                inventory.parsedFiles(), failures);
    }

    private static Set<String> projectPackagesOf(SqliteStore store) {
        return store.queryPackagesByKinds(GraphConstants.INDEX_SUMMARY_TYPE_KINDS);
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
