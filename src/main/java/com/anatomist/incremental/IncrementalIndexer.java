package com.anatomist.incremental;

import com.anatomist.config.ProjectConfig;
import com.anatomist.framework.AnalysisContext;
import com.anatomist.framework.AnalyzerRegistry;
import com.anatomist.core.ExtractionContext;
import com.anatomist.core.GraphPostProcessor;
import com.anatomist.core.JavaParserFactory;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.core.ProjectScanner;
import com.anatomist.core.SpringBeanParser;
import com.anatomist.core.WiringResolver;
import com.anatomist.extract.ExtractorPipeline;
import com.anatomist.extract.TypeExtractor;
import com.anatomist.extract.XmlBeanExtractor;
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
        this.projectRoot = projectRoot;
        this.sourcePaths = sourcePaths;
        this.parserFactory = parserFactory;
        this.store = store;
        this.javaVersion = javaVersion;
        this.maxRealignFiles = maxRealignFiles;
        this.springXml = springXml;
        this.projectConfig = projectConfig != null ? projectConfig : new ProjectConfig();
    }

    public static final class Summary {
        public int changedFiles;
        public int newFiles;
        public int deletedFiles;
        public int newNodes;
        public int newEdges;
        public int realignedDependents;
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

                Set<Path> targetAbs = new HashSet<>();
                for (String rel : toReparse) {
                    targetAbs.add(projectRoot.resolve(rel).toAbsolutePath().normalize());
                }

                parserFactory.parseAll((filePath, cu) -> {
                    if (filePath == null) return;
                    Path abs = filePath.toAbsolutePath().normalize();
                    if (!targetAbs.contains(abs)) return;
                    String relative;
                    try {
                        relative = projectRoot.relativize(abs).toString();
                    } catch (IllegalArgumentException ex) {
                        relative = abs.toString();
                    }
                    cu.setData(TypeExtractor.SourceFileKey.KEY, relative);
                    pipeline.extractAll(cu, result);
                });

                Set<String> knownIds = store.allNodeIds();
                new GraphPostProcessor().process(result, knownIds);
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
                store.deleteSpringBeanGraph();
                rebuiltXml = new ProjectScanner().scanSpringXml(projectRoot);
                if (!rebuiltXml.isEmpty()) {
                    Set<String> knownIds = store.allNodeIds();
                    XmlBeanExtractor xmlExtractor = new XmlBeanExtractor("MAIN");
                    SpringBeanParser beanParser = new SpringBeanParser();
                    for (Path xml : rebuiltXml) {
                        Path abs = xml.toAbsolutePath().normalize();
                        String rel;
                        try {
                            rel = projectRoot.relativize(abs).toString();
                        } catch (IllegalArgumentException ex) {
                            rel = abs.toString();
                        }
                        xmlExtractor.extract(beanParser.parse(xml), knownIds, rel, beanResult);
                    }
                    new GraphPostProcessor().process(beanResult, knownIds);
                    store.writeInCurrentTransaction(beanResult);
                }
            }

            List<Edge> generatedWiring = new WiringResolver().resolve(store.readWiringSourceEdges());
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

            s.newNodes = result.nodes.size() + beanResult.nodes.size();
            s.newEdges = result.edges.size() + beanResult.edges.size();

            // Dependents were reparsed in this same pass and are now aligned, so the
            // re-derived file_dependencies reflects the new state with nothing stale.
            store.refreshFileDependencies();
        });
        return s;
    }

    private static boolean touchesSpringXml(Set<String> reparse, Set<String> delete) {
        for (String f : reparse) if (f.endsWith(".xml")) return true;
        for (String f : delete) if (f.endsWith(".xml")) return true;
        return false;
    }

}
