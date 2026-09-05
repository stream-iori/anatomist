package com.anatomist.incremental;

import com.anatomist.config.ProjectConfig;
import com.anatomist.framework.AnalysisContext;
import com.anatomist.framework.spring.BuiltInExtensions;
import com.anatomist.framework.DefaultProjectFactView;
import com.anatomist.framework.PreparedExtensions;
import com.anatomist.framework.ProjectAnalysisRunner;
import com.anatomist.framework.ProjectResource;
import com.anatomist.framework.ProjectResourceDiscovery;
import com.anatomist.framework.ExtensionReport;
import com.anatomist.core.ExtractionContext;
import com.anatomist.core.GraphPostProcessor;
import com.anatomist.core.JavaParserFactory;
import com.anatomist.incremental.JavaContractFingerprint;
import com.anatomist.core.IndexTimings;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.core.NodeKeyFactory;
import com.anatomist.core.GraphIdentityRewriter;
import com.anatomist.core.ProjectScanner;
import com.anatomist.core.SourceIdentityResolver;
import com.anatomist.core.SourceRoot;
import com.anatomist.core.WiringResolver;
import com.anatomist.extract.ExtractorPipeline;
import com.anatomist.extract.TypeExtractor;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.model.Node;
import com.anatomist.store.FileCacheService;
import com.anatomist.store.SqliteStore;
import com.anatomist.store.StagedGraphStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class IncrementalIndexer {

    private static final int MAX_PARSE_BATCH_FILES = 128;

    private final Path projectRoot;
    private final List<Path> sourcePaths;
    private final JavaParserFactory parserFactory;
    private final SqliteStore store;
    private final int javaVersion;
    private final int maxRealignFiles;
    private final boolean springXml;
    private final ProjectConfig projectConfig;
    private final List<SourceRoot> sourceRoots;
    private final IndexTimings timings;

    public IncrementalIndexer(Path projectRoot,
                              List<Path> sourcePaths,
                              JavaParserFactory parserFactory,
                              SqliteStore store,
                              int javaVersion,
                              int maxRealignFiles) {
        this(projectRoot, sourcePaths, parserFactory, store, javaVersion, maxRealignFiles, false, new ProjectConfig());
    }

    public IncrementalIndexer(Path projectRoot,
                              List<Path> sourcePaths,
                              JavaParserFactory parserFactory,
                              SqliteStore store,
                              int javaVersion,
                              int maxRealignFiles,
                              boolean springXml) {
        this(projectRoot, sourcePaths, parserFactory, store, javaVersion, maxRealignFiles, springXml, new ProjectConfig());
    }

    public IncrementalIndexer(Path projectRoot,
                              List<Path> sourcePaths,
                              JavaParserFactory parserFactory,
                              SqliteStore store,
                              int javaVersion,
                              int maxRealignFiles,
                              boolean springXml,
                              ProjectConfig projectConfig) {
        this(projectRoot, sourcePaths, parserFactory, store, javaVersion, maxRealignFiles,
                springXml, projectConfig, SourceIdentityResolver.inferRoots(projectRoot, sourcePaths));
    }

    public IncrementalIndexer(Path projectRoot,
                              List<Path> sourcePaths,
                              JavaParserFactory parserFactory,
                              SqliteStore store,
                              int javaVersion,
                              int maxRealignFiles,
                              boolean springXml,
                              ProjectConfig projectConfig,
                              List<SourceRoot> sourceRoots) {
        this(projectRoot, sourcePaths, parserFactory, store, javaVersion, maxRealignFiles,
                springXml, projectConfig, sourceRoots, null);
    }

    public IncrementalIndexer(Path projectRoot,
                              List<Path> sourcePaths,
                              JavaParserFactory parserFactory,
                              SqliteStore store,
                              int javaVersion,
                              int maxRealignFiles,
                              boolean springXml,
                              ProjectConfig projectConfig,
                              List<SourceRoot> sourceRoots,
                              IndexTimings timings) {
        this.projectRoot = projectRoot;
        this.sourcePaths = sourcePaths;
        this.parserFactory = parserFactory;
        this.store = store;
        this.javaVersion = javaVersion;
        this.maxRealignFiles = maxRealignFiles;
        this.springXml = springXml;
        this.projectConfig = projectConfig != null ? projectConfig : new ProjectConfig();
        this.sourceRoots = sourceRoots == null ? List.of() : List.copyOf(sourceRoots);
        this.timings = timings;
        this.parserFactory.setTimings(timings);
    }

    public static final class Summary {
        public int changedFiles;
        public int newFiles;
        public int deletedFiles;
        public int deletedNodes;
        public int deletedEdges;
        public int writtenNodes;
        public int writtenEdges;
        public int realignedDependents;
        public int reparsedFiles;
        public long unresolvedSymbols;
        public int droppedDanglingFacts;
        public final Map<String, Long> extensionCounters = new LinkedHashMap<>();
        public boolean degradedToFull;
        public String degradationReason;
    }

    public Summary indexIncremental(List<String> changedFiles,
                                    List<String> newFiles,
                                    List<String> deletedFiles,
                                    Map<String, String> diskHashes) {
        long incrementalStarted = System.nanoTime();
        Summary s = new Summary();
        s.changedFiles = changedFiles.size();
        s.newFiles = newFiles.size();
        s.deletedFiles = deletedFiles.size();

        Set<String> primaryFiles = new LinkedHashSet<>();
        primaryFiles.addAll(changedFiles);
        primaryFiles.addAll(newFiles);
        Set<String> toDelete = new LinkedHashSet<>();
        toDelete.addAll(changedFiles);
        toDelete.addAll(deletedFiles);
        int javaFileCount = (int) diskHashes.keySet().stream()
                .filter(path -> path.endsWith(".java")).count();
        PerformanceHistory.Model costModel = PerformanceHistory.read(
                store, maxRealignFiles, javaFileCount);
        PerformanceHistory.Decision primaryDecision = costModel.decide(
                primaryFiles.size(), 0, elapsedMillis(incrementalStarted));
        if (!primaryDecision.incremental()) {
            degrade(s, primaryDecision);
            return s;
        }

        List<Path> deletedJavaPaths = deletedFiles.stream().filter(path -> path.endsWith(".java"))
                .map(projectRoot::resolve).toList();
        Map<String, Node> deletedNodeSnapshot = deletedJavaPaths.isEmpty() ? Map.of()
                : store.readNodesBySourceFiles(deletedFiles.stream()
                        .filter(path -> path.endsWith(".java")).toList());
        Set<String> processedJava = new LinkedHashSet<>();
        Set<String> pendingJava = javaFiles(primaryFiles, diskHashes);
        Set<String> realignTargets = new LinkedHashSet<>();
        long knownIdsStarted = startTiming();
        Set<String> knownIds = new HashSet<>(store.allNodeIds());
        knownIds.removeAll(deletedNodeSnapshot.keySet());
        stopTiming("known_ids", knownIdsStarted);
        Map<String, FileCacheEntry> priorFileCache = store.readFileCache();
        Map<String, String> contractHashes = new LinkedHashMap<>();
        List<com.anatomist.core.IndexDiagnostic> resolutionDiagnostics = new ArrayList<>();
        boolean javaContractChanged = !deletedJavaPaths.isEmpty();
        SourceIdentityResolver identities = sourceRoots.isEmpty()
                ? new SourceIdentityResolver(projectRoot, sourcePaths)
                : SourceIdentityResolver.fromRoots(projectRoot, sourceRoots);

        long stagingStarted = startTiming();
        StagedGraphStore stagingStore = new StagedGraphStore(store.dbPath(), identities);
        stopTiming("staging_setup", stagingStarted);
        try (StagedGraphStore staging = stagingStore) {

        Set<String> deletedJava = new LinkedHashSet<>();
        for (String file : deletedFiles) if (file.endsWith(".java")) deletedJava.add(file);
        if (!deletedJava.isEmpty()) {
            long deltaStarted = startTiming();
            SymbolGraphDelta.Impact deletedImpact = SymbolGraphDelta.analyze(
                    deletedNodeSnapshot, List.of());
            stopTiming("symbol_delta", deltaStarted);
            long impactStarted = startTiming();
            Set<String> candidates = impactedSourceFiles(deletedImpact);
            stopTiming("impact_analysis", impactStarted);
            candidates.removeAll(primaryFiles);
            candidates.removeAll(deletedFiles);
            candidates.removeIf(file -> !file.endsWith(".java") || !diskHashes.containsKey(file));
            realignTargets.addAll(candidates);
            pendingJava.addAll(candidates);
            int totalImpact = primaryFiles.size() + realignTargets.size();
            PerformanceHistory.Decision decision = costModel.decide(
                    totalImpact, processedJava.size(), elapsedMillis(incrementalStarted));
            if (!decision.incremental()) {
                degrade(s, decision);
                return s;
            }
        }

        while (!pendingJava.isEmpty()) {
            Set<String> batchFiles = pendingJava.stream().limit(MAX_PARSE_BATCH_FILES)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            pendingJava.removeAll(batchFiles);
            Map<String, Node> oldNodes = store.readNodesBySourceFiles(new ArrayList<>(batchFiles));

            long parseStarted = startTiming();
            BatchExtraction batch = extractJavaFiles(batchFiles, priorFileCache);
            Set<String> missing = new LinkedHashSet<>(batchFiles);
            missing.removeAll(batch.parsedFiles());
            if (!missing.isEmpty()) {
                Map<String, List<String>> diagnostics = new LinkedHashMap<>();
                for (String file : missing) {
                    diagnostics.put(file, batch.parseProblems().getOrDefault(
                            file, List.of("parser produced no compilation unit")));
                }
                throw new IncrementalParseException(new ArrayList<>(missing), diagnostics);
            }
            contractHashes.putAll(batch.contractHashes());
            javaContractChanged |= batch.contractChanged();

            Set<String> survivingIds = new HashSet<>(knownIds);
            survivingIds.removeAll(oldNodes.keySet());
            GraphIdentityRewriter.rewrite(batch.result(), identities, survivingIds);
            Set<String> batchNodeIds = new HashSet<>();
            for (Node node : batch.result().nodes) batchNodeIds.add(node.id);
            Set<String> postProcessIds = new HashSet<>(survivingIds);
            postProcessIds.addAll(batchNodeIds);
            GraphPostProcessor.Summary post = new GraphPostProcessor()
                    .process(batch.result(), postProcessIds);
            s.droppedDanglingFacts += post.droppedDanglingFacts();
            s.unresolvedSymbols += batch.unresolvedSymbols();
            resolutionDiagnostics.addAll(batch.resolutionDiagnostics());
            resolutionDiagnostics.addAll(batch.extensionDiagnostics());
            batch.extensionCounters().forEach((key, value) ->
                    s.extensionCounters.merge(key, value, Long::sum));
            stopTiming("parse_extract", parseStarted);

            long deltaStarted = startTiming();
            SymbolGraphDelta.Impact impact = SymbolGraphDelta.analyze(oldNodes, batch.result().nodes);
            stopTiming("symbol_delta", deltaStarted);
            long impactStarted = startTiming();
            Set<String> candidates = impactedSourceFiles(impact);
            stopTiming("impact_analysis", impactStarted);

            processedJava.addAll(batchFiles);
            long stageStarted = startTiming();
            staging.writeNormalizedBatch(batch.result());
            batch.result().clearFacts();
            stopTiming("stage_write", stageStarted);
            knownIds = postProcessIds;

            candidates.removeAll(processedJava);
            candidates.removeAll(deletedFiles);
            candidates.removeIf(file -> !file.endsWith(".java") || !diskHashes.containsKey(file));
            realignTargets.addAll(candidates);
            int totalImpact = primaryFiles.size() + realignTargets.size();
            PerformanceHistory.Decision decision = costModel.decide(
                    totalImpact, processedJava.size(), elapsedMillis(incrementalStarted));
            if (!decision.incremental()) {
                degrade(s, decision);
                return s;
            }
            if (!candidates.isEmpty()) {
                pendingJava.addAll(candidates);
            }
        }
        s.realignedDependents = realignTargets.size();
        s.reparsedFiles = processedJava.size();

        Set<String> toReparse = new LinkedHashSet<>(primaryFiles);
        toReparse.addAll(realignTargets);
        Set<String> replaceFiles = new LinkedHashSet<>(toReparse);
        replaceFiles.addAll(deletedFiles);
        ExtractionContext projectCtx = new ExtractionContext(
                projectRoot, sourcePaths, new NodeIdGenerator(), null, "MAIN", projectConfig);
        AnalysisContext projectAnalysisContext = new AnalysisContext(
                projectRoot, sourcePaths, projectCtx, projectConfig, springXml);
        PreparedExtensions projectExtensions = BuiltInExtensions.prepare(projectAnalysisContext);
        Set<String> resourceChanges = new LinkedHashSet<>(toReparse);
        resourceChanges.addAll(toDelete);
        boolean resourceTouched = projectExtensions.registry().projectResourceProviders().stream()
                .filter(provider -> provider.enabled(projectAnalysisContext))
                .anyMatch(provider -> resourceChanges.stream()
                        .map(projectRoot::resolve).anyMatch(provider::mayContain));
        boolean rebuildProjectResources = !projectExtensions.registry().projectResourceProviders().isEmpty()
                && (resourceTouched || javaContractChanged);
        boolean rebuildDerivedWiring = javaContractChanged;
        List<String> affectedFiles = new ArrayList<>(replaceFiles);
        List<Path> rebuiltXml = new ArrayList<>();
        Set<String> rebuiltProjectProducers = new LinkedHashSet<>();
        if (rebuildProjectResources) {
            long springStarted = startTiming();
            ExtensionReport projectReport = new ExtensionReport(timings);
            List<ProjectResource> resources = new ProjectResourceDiscovery().discover(
                    projectExtensions, projectAnalysisContext, new ProjectScanner(), projectReport);
            rebuiltXml = resources.stream().map(ProjectResource::path).toList();
            Set<String> activeKinds = projectExtensions.registry().projectResourceProviders().stream()
                    .filter(provider -> provider.enabled(projectAnalysisContext))
                    .flatMap(provider -> provider.kinds().stream()).collect(java.util.stream.Collectors.toSet());
            projectExtensions.registry().projectResourceAnalyzers().stream()
                    .filter(analyzer -> analyzer.enabled(projectAnalysisContext))
                    .filter(analyzer -> activeKinds.stream().anyMatch(kind ->
                            analyzer.selector().matches(new ProjectResource(projectRoot, "", kind))))
                    .map(com.anatomist.framework.ProjectResourceAnalyzer::producerId)
                    .forEach(rebuiltProjectProducers::add);
            if (!resources.isEmpty()) {
                Set<String> extractionIds = new HashSet<>(knownIds);
                knownIds.stream().map(NodeKeyFactory::symbolId).forEach(extractionIds::add);
                extractionIds.addAll(staging.allSymbolIds());
                Map<String, com.anatomist.model.BeanRefTarget> beanTargets =
                        new LinkedHashMap<>(com.anatomist.framework.spring.SpringXmlAnalyzer
                                .fromBeanClassMap(store.readBeanClassTargets()));
                beanTargets.putAll(staging.rawBeanTargets());
                ExtractionResult beanResult = new ExtractionResult();
                new ProjectAnalysisRunner().run(projectExtensions, projectAnalysisContext, resources,
                        new DefaultProjectFactView(extractionIds, beanTargets), beanResult, projectReport);
                GraphIdentityRewriter.rewrite(beanResult, identities, knownIds);
                new GraphPostProcessor().process(beanResult, knownIds);
                staging.writeNormalizedBatch(beanResult);
                beanResult.clearFacts();
            }
            resolutionDiagnostics.addAll(projectReport.diagnostics());
            stopTiming("spring_graph", springStarted);
        }

        long graphStarted = startTiming();
        StagedGraphStore.IncrementalPromotionStats promoted =
                staging.promoteIncremental(store, affectedFiles, rebuiltProjectProducers,
                        rebuildDerivedWiring);
        stopTiming("stage_promote", graphStarted);
        if (timings != null) {
            timings.addNanos("call_site_persistence", promoted.callSitePersistenceNanos());
        }
        stopTiming("graph_replace", graphStarted);
        stopTiming("graph_write", graphStarted);
        s.deletedNodes += promoted.deletedNodes();
        s.deletedEdges += promoted.deletedEdges();
        s.writtenNodes = promoted.writtenNodes();
        s.writtenEdges = promoted.writtenEdges();
        Map<String, FileCacheService.SourceFileStats> perFile = staging.sourceFileStats();
        String now = Instant.now().toString();
        LinkedHashSet<String> cacheTargets = new LinkedHashSet<>(toReparse);
        for (Path xml : rebuiltXml) cacheTargets.add(relativePath(xml));
        List<FileCacheEntry> entries = FileCacheService.buildEntries(
                projectRoot, new ArrayList<>(cacheTargets), diskHashes, perFile, now, contractHashes);
        if (!entries.isEmpty()) store.updateFileCache(entries);

        long dependenciesStarted = startTiming();
        Set<String> dependencyFiles = new LinkedHashSet<>(replaceFiles);
        for (Path xml : rebuiltXml) dependencyFiles.add(relativePath(xml));
        store.refreshFileDependencies(new ArrayList<>(dependencyFiles));
        stopTiming("file_dependencies", dependenciesStarted);
        store.replaceIndexDiagnosticsForFiles(affectedFiles, resolutionDiagnostics);
        ExtractionContext fingerprintCtx = new ExtractionContext(
                projectRoot, sourcePaths, new NodeIdGenerator(), null, "MAIN", projectConfig);
        PreparedExtensions fingerprintExtensions = BuiltInExtensions.prepare(new AnalysisContext(
                projectRoot, sourcePaths, fingerprintCtx, projectConfig, springXml));
        store.upsertProjectMeta(Map.of(PreparedExtensions.META_KEY, fingerprintExtensions.fingerprint()));
        store.upsertProjectMeta(com.anatomist.framework.lombok.LombokIndexMetadata.snapshot(
                projectConfig, fingerprintExtensions, store, resolutionDiagnostics));
        return s;
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static void degrade(Summary summary, PerformanceHistory.Decision decision) {
        summary.degradedToFull = true;
        summary.degradationReason = decision.reason();
    }

    private BatchExtraction extractJavaFiles(Set<String> files,
                                             Map<String, FileCacheEntry> priorFileCache) {
        ExtractionResult result = new ExtractionResult();
        NodeIdGenerator idGen = new NodeIdGenerator();
        ExtractionContext ctx = new ExtractionContext(
                projectRoot, sourcePaths, idGen, null, "MAIN", projectConfig);
        AnalysisContext analysisContext = new AnalysisContext(
                projectRoot, sourcePaths, ctx, projectConfig, springXml);
        long extensionPrepareStarted = startTiming();
        PreparedExtensions extensions = BuiltInExtensions.prepare(analysisContext);
        stopTiming("extension_prepare", extensionPrepareStarted);
        ExtensionReport extensionReport = new ExtensionReport(timings);
        ExtractorPipeline pipeline = new ExtractorPipeline(
                ctx, extensions.registry().callSiteEvidenceProviders(),
                extensions.registry().javaUnitAnalyzers(), timings, extensionReport);
        Set<String> parsed = new LinkedHashSet<>();
        JavaParserFactory.ParseFilesResult parsedBatch =
                parserFactory.parseFilesDetailed(targetJavaFiles(files));
        Map<String, List<String>> parseProblems = new LinkedHashMap<>();
        parsedBatch.problems().forEach((path, problems) ->
                parseProblems.put(relativePath(path), problems));
        for (var cu : parsedBatch.compilationUnits()) {
            cu.getStorage().ifPresent(storage -> parsed.add(relativePath(
                    storage.getPath().toAbsolutePath().normalize())));
        }
        Set<String> missing = new LinkedHashSet<>(files);
        missing.removeAll(parsed);
        if (!missing.isEmpty()) {
            return new BatchExtraction(result, parsed, ctx.unresolvedCount(), parseProblems,
                    Map.of(), false, List.of(), extensionReport.diagnostics(),
                    extensionReport.counters());
        }

        Map<String, String> batchContractHashes = new LinkedHashMap<>();
        boolean contractChanged = false;
        for (var cu : parsedBatch.compilationUnits()) {
            Path abs = cu.getStorage().map(storage -> storage.getPath().toAbsolutePath().normalize())
                    .orElse(null);
            if (abs == null) continue;
            String relative = relativePath(abs);
            String contractHash = JavaContractFingerprint.of(cu);
            batchContractHashes.put(relative, contractHash);
            FileCacheEntry prior = priorFileCache.get(relative);
            if (prior == null || prior.contractHash() == null
                    || !contractHash.equals(prior.contractHash())) {
                contractChanged = true;
            }
        }
        parsed.clear();
        for (var cu : parsedBatch.compilationUnits()) {
            Path abs = cu.getStorage()
                    .map(storage -> storage.getPath().toAbsolutePath().normalize())
                    .orElse(null);
            if (abs == null) continue;
            String relative = relativePath(abs);
            parsed.add(relative);
            cu.setData(TypeExtractor.SourceFileKey.KEY, relative);
            pipeline.extractAll(cu, result);
        }
        boolean noClasspath = "none".equals(store.readProjectMeta("classpath_mode").orElse(""));
        return new BatchExtraction(result, parsed, ctx.unresolvedCount(), parseProblems,
                Map.copyOf(batchContractHashes), contractChanged,
                ctx.resolutionSummary(noClasspath).diagnostics(), extensionReport.diagnostics(),
                extensionReport.counters());
    }

    private Set<String> impactedSourceFiles(SymbolGraphDelta.Impact impact) {
        Set<String> out = new LinkedHashSet<>();
        long exactStarted = startTiming();
        out.addAll(store.sourceFilesReferencingNodeIds(impact.exactTargetIds()));
        stopTiming("impact_exact", exactStarted);
        long prefixStarted = startTiming();
        out.addAll(store.sourceFilesReferencingOwnerIds(impact.ownerTargetIds()));
        out.addAll(store.sourceFilesImplementingTypeIds(impact.implementorTargetIds()));
        out.addAll(store.sourceFilesMatchingExternalTargets(impact.externalPrefixes()));
        out.addAll(store.sourceFilesMatchingExactExternalTargets(impact.externalExactTargets()));
        stopTiming("impact_prefix", prefixStarted);
        return out;
    }

    private static Set<String> javaFiles(Set<String> files, Map<String, String> diskHashes) {
        Set<String> out = new LinkedHashSet<>();
        for (String file : files) {
            if (file.endsWith(".java") && diskHashes.containsKey(file)) out.add(file);
        }
        return out;
    }

    private String relativePath(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        try {
            return projectRoot.relativize(abs).toString();
        } catch (IllegalArgumentException ex) {
            return abs.toString();
        }
    }

    private record BatchExtraction(ExtractionResult result,
                                   Set<String> parsedFiles,
                                   long unresolvedSymbols,
                                   Map<String, List<String>> parseProblems,
                                   Map<String, String> contractHashes,
                                   boolean contractChanged,
                                   List<com.anatomist.core.IndexDiagnostic> resolutionDiagnostics,
                                   List<com.anatomist.core.IndexDiagnostic> extensionDiagnostics,
                                   Map<String, Long> extensionCounters) {}

    private long startTiming() {
        return timings == null ? 0L : timings.start();
    }

    private void stopTiming(String phase, long started) {
        if (timings != null) timings.stop(phase, started);
    }

    private static Set<String> wiringFactKeys(List<Edge> edges) {
        Set<String> out = new HashSet<>();
        if (edges == null) return out;
        for (Edge edge : edges) {
            if (!isRawWiringFact(edge)) continue;
            out.add(edge.relation + "|" + edge.sourceId + "|" + edge.targetId + "|"
                    + edge.externalTargetFqn + "|" + edge.callKind + "|" + edge.context + "|"
                    + edge.sourceLocation + "|" + edge.isExternal);
        }
        return out;
    }

    private static boolean isRawWiringFact(Edge edge) {
        if (edge == null || WiringResolver.isGenerated(edge)) return false;
        return com.anatomist.model.GraphConstants.Relation.INJECTS.equals(edge.relation)
                || com.anatomist.model.GraphConstants.Relation.IMPLEMENTS.equals(edge.relation)
                || com.anatomist.model.GraphConstants.Relation.OVERRIDES.equals(edge.relation)
                || com.anatomist.model.GraphConstants.Relation.CALLS.equals(edge.relation);
    }

    private List<Path> targetJavaFiles(Set<String> toReparse) {
        List<Path> out = new ArrayList<>();
        for (String rel : toReparse) {
            if (rel.endsWith(".java")) {
                out.add(projectRoot.resolve(rel).toAbsolutePath().normalize());
            }
        }
        return out;
    }

}
