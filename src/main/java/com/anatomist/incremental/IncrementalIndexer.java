package com.anatomist.incremental;

import com.anatomist.config.ProjectConfig;
import com.anatomist.framework.AnalysisContext;
import com.anatomist.framework.AnalyzerRegistry;
import com.anatomist.core.ExtractionContext;
import com.anatomist.core.GraphPostProcessor;
import com.anatomist.core.JavaParserFactory;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.core.NodeKeyFactory;
import com.anatomist.core.GraphIdentityRewriter;
import com.anatomist.core.ProjectScanner;
import com.anatomist.core.SourceIdentityResolver;
import com.anatomist.core.SourceRoot;
import com.anatomist.core.WiringResolver;
import com.anatomist.extract.ExtractorPipeline;
import com.anatomist.extract.TypeExtractor;
import com.anatomist.framework.spring.SpringXmlAnalyzer;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.store.FileCacheService;
import com.anatomist.store.SqliteStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class IncrementalIndexer {

    private final Path projectRoot;
    private final List<Path> sourcePaths;
    private final JavaParserFactory parserFactory;
    private final SqliteStore store;
    private final int javaVersion;
    private final int maxRealignFiles;
    private final boolean springXml;
    private final ProjectConfig projectConfig;
    private final List<SourceRoot> sourceRoots;

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
        this.projectRoot = projectRoot;
        this.sourcePaths = sourcePaths;
        this.parserFactory = parserFactory;
        this.store = store;
        this.javaVersion = javaVersion;
        this.maxRealignFiles = maxRealignFiles;
        this.springXml = springXml;
        this.projectConfig = projectConfig != null ? projectConfig : new ProjectConfig();
        this.sourceRoots = sourceRoots == null ? List.of() : List.copyOf(sourceRoots);
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
        public long unresolvedSymbols;
        public int droppedDanglingFacts;
        public boolean degradedToFull;
        public String degradationReason;
    }

    public Summary indexIncremental(List<String> changedFiles,
                                    List<String> newFiles,
                                    List<String> deletedFiles,
                                    Map<String, String> diskHashes) {
        Summary s = new Summary();
        s.changedFiles = changedFiles.size();
        s.newFiles = newFiles.size();
        s.deletedFiles = deletedFiles.size();

        Set<String> toReparse = new LinkedHashSet<>();
        toReparse.addAll(changedFiles);
        toReparse.addAll(newFiles);

        Set<String> toDelete = new LinkedHashSet<>();
        toDelete.addAll(changedFiles);
        toDelete.addAll(deletedFiles);

        // Transitively realign dependents of changed/deleted files (not of new files —
        // a brand-new file has no dependents yet). Closure is computed against the
        // *current* file_dependencies table, which reflects the last committed state.
        Set<String> realignTargets = new LinkedHashSet<>();
        Set<String> seed = new LinkedHashSet<>();
        seed.addAll(changedFiles);
        seed.addAll(deletedFiles);
        Set<String> frontier = seed;
        Set<String> visited = new HashSet<>(seed);
        while (!frontier.isEmpty()) {
            Set<String> next = store.dependentsOf(new ArrayList<>(frontier));
            Set<String> fresh = new LinkedHashSet<>();
            for (String dep : next) {
                if (visited.add(dep)) fresh.add(dep);
            }
            frontier = fresh;
            for (String dep : fresh) {
                // Only realign dependents still present on disk; vanished ones are
                // handled via the deleted path of detectChanges.
                if (diskHashes.containsKey(dep)
                        && !toReparse.contains(dep)
                        && !deletedFiles.contains(dep)) {
                    realignTargets.add(dep);
                }
            }
        }
        toReparse.addAll(realignTargets);
        toDelete.addAll(realignTargets);

        if (toReparse.size() > maxRealignFiles) {
            s.degradedToFull = true;
            s.degradationReason = "realign closure " + toReparse.size()
                    + ">" + maxRealignFiles;
            return s;
        }
        s.realignedDependents = realignTargets.size();

        store.inTransaction(c -> {
            if (!toDelete.isEmpty()) {
                FileCacheService.SourceFileStats deleted = store.countRowsDeletedBySourceFiles(new ArrayList<>(toDelete));
                s.deletedNodes += deleted.nodeCount();
                s.deletedEdges += deleted.edgeCount();
                store.deleteBySourceFiles(new ArrayList<>(toDelete));
            }

            ExtractionResult result = new ExtractionResult();
            if (!toReparse.isEmpty()) {
                NodeIdGenerator idGen = new NodeIdGenerator();
                ExtractionContext ctx = new ExtractionContext(projectRoot, sourcePaths, idGen, null, "MAIN", projectConfig);
                AnalysisContext analysisContext = new AnalysisContext(
                        projectRoot, sourcePaths, ctx, projectConfig, springXml);
                ExtractorPipeline pipeline = new ExtractorPipeline(
                        ctx, AnalyzerRegistry.javaAstAnalyzers(analysisContext));

                List<Path> targetJavaFiles = targetJavaFiles(toReparse);
                for (var cu : parserFactory.parseFiles(targetJavaFiles)) {
                    Path abs = cu.getStorage()
                            .map(storage -> storage.getPath().toAbsolutePath().normalize())
                            .orElse(null);
                    if (abs == null) continue;
                    String relative;
                    try {
                        relative = projectRoot.relativize(abs).toString();
                    } catch (IllegalArgumentException ex) {
                        relative = abs.toString();
                    }
                    cu.setData(TypeExtractor.SourceFileKey.KEY, relative);
                    pipeline.extractAll(cu, result);
                }

                Set<String> knownIds = store.allNodeIds();
                GraphIdentityRewriter.rewrite(result,
                        sourceRoots.isEmpty()
                                ? new SourceIdentityResolver(projectRoot, sourcePaths)
                                : SourceIdentityResolver.fromRoots(projectRoot, sourceRoots), knownIds);
                GraphPostProcessor.Summary post = new GraphPostProcessor().process(result, knownIds);
                s.droppedDanglingFacts += post.droppedDanglingFacts();
                s.unresolvedSymbols += ctx.unresolvedCount();
                store.writeInCurrentTransaction(result);
            }

            // Spring-bean graph rebuild. bean refs can cross XML files, so whenever
            // any <beans> XML enters the reparse/delete closure (edited directly, or
            // pulled in via file_dependencies because a referenced Java class changed,
            // or deleted) we wipe the whole bean subgraph and re-derive it from every
            // XML on disk against the just-written Java node set. beans are few, so the
            // wholesale rebuild is cheap and immune to partial cross-file state.
            ExtractionResult beanResult = new ExtractionResult();
            List<Path> rebuiltXml = new ArrayList<>();
            if (springXml && touchesSpringXml(toReparse, toDelete)) {
                FileCacheService.SourceFileStats deleted = store.countSpringBeanGraphRows();
                s.deletedNodes += deleted.nodeCount();
                s.deletedEdges += deleted.edgeCount();
                store.deleteSpringBeanGraph();
                rebuiltXml = new ProjectScanner().scanSpringXml(projectRoot);
                if (!rebuiltXml.isEmpty()) {
                    Set<String> storageIds = store.allNodeIds();
                    Set<String> extractionIds = new HashSet<>(storageIds);
                    storageIds.stream().map(NodeKeyFactory::symbolId).forEach(extractionIds::add);
                    SpringXmlAnalyzer.extractXmlBeans(projectRoot, rebuiltXml, extractionIds,
                            SpringXmlAnalyzer.fromBeanClassMap(store.readBeanClassTargets()), beanResult);
                    GraphIdentityRewriter.rewrite(beanResult,
                            sourceRoots.isEmpty()
                                    ? new SourceIdentityResolver(projectRoot, sourcePaths)
                                    : SourceIdentityResolver.fromRoots(projectRoot, sourceRoots), storageIds);
                    new GraphPostProcessor().process(beanResult, storageIds);
                    store.writeInCurrentTransaction(beanResult);
                }
            }

            List<Edge> generatedWiring = new WiringResolver().resolve(store.readWiringSourceEdges());
            s.deletedEdges += store.countGeneratedWiringEdges();
            store.replaceGeneratedWiringEdgesInCurrentTransaction(generatedWiring);

            // Count nodes/edges per source file in this batch (Java + bean rebuild)
            Map<String, FileCacheService.SourceFileStats> perFile =
                    FileCacheService.countBySourceFile(result, beanResult);

            String now = Instant.now().toString();
            LinkedHashSet<String> cacheTargets = new LinkedHashSet<>(toReparse);
            for (Path xml : rebuiltXml) {
                Path abs = xml.toAbsolutePath().normalize();
                String rel;
                try {
                    rel = projectRoot.relativize(abs).toString();
                } catch (IllegalArgumentException ex) {
                    rel = abs.toString();
                }
                cacheTargets.add(rel);
            }
            List<FileCacheEntry> entries = FileCacheService.buildEntries(
                    new ArrayList<>(cacheTargets), diskHashes, perFile, now);
            if (!entries.isEmpty()) store.updateFileCache(entries);

            s.writtenNodes = result.nodes.size() + beanResult.nodes.size();
            s.writtenEdges = result.edges.size() + beanResult.edges.size() + generatedWiring.size();

            // Dependents were reparsed in this same pass and are now aligned, so the
            // re-derived file_dependencies reflects the new state with nothing stale.
            store.refreshFileDependencies();
        });
        store.replaceIndexDiagnostics(com.anatomist.core.IndexHealthService
                .fromCounts(s.unresolvedSymbols, s.droppedDanglingFacts).diagnostics());
        return s;
    }

    private static boolean touchesSpringXml(Set<String> reparse, Set<String> delete) {
        for (String f : reparse) if (f.endsWith(".xml")) return true;
        for (String f : delete) if (f.endsWith(".xml")) return true;
        return false;
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
