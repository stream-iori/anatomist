package com.anatomist.incremental;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.JavaParserFactory;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.extract.AnnotationExtractor;
import com.anatomist.extract.CallGraphExtractor;
import com.anatomist.extract.FieldAccessExtractor;
import com.anatomist.extract.FieldExtractor;
import com.anatomist.extract.HierarchyExtractor;
import com.anatomist.extract.MethodExtractor;
import com.anatomist.extract.ReferenceExtractor;
import com.anatomist.extract.TypeExtractor;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.model.Node;
import com.anatomist.semantic.SemanticPostProcessor;
import com.anatomist.store.SqliteStore;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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

    public IncrementalIndexer(Path projectRoot,
                              List<Path> sourcePaths,
                              JavaParserFactory parserFactory,
                              SqliteStore store,
                              int javaVersion) {
        this.projectRoot = projectRoot;
        this.sourcePaths = sourcePaths;
        this.parserFactory = parserFactory;
        this.store = store;
        this.javaVersion = javaVersion;
    }

    public static final class Summary {
        public int changedFiles;
        public int newFiles;
        public int deletedFiles;
        public int newNodes;
        public int newEdges;
        public List<String> staleAfter = new ArrayList<>();
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

        Connection c;
        try {
            c = store.connection();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire connection", e);
        }
        boolean priorAutoCommit;
        try {
            priorAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to begin transaction", e);
        }
        try {
            if (!toDelete.isEmpty()) {
                store.deleteBySourceFiles(new ArrayList<>(toDelete));
            }

            ExtractionResult result = new ExtractionResult();
            if (!toReparse.isEmpty()) {
                NodeIdGenerator idGen = new NodeIdGenerator();
                ExtractionContext ctx = new ExtractionContext(projectRoot, sourcePaths, idGen, null, "MAIN");
                TypeExtractor typeExtractor = new TypeExtractor(ctx);
                FieldExtractor fieldExtractor = new FieldExtractor(ctx);
                MethodExtractor methodExtractor = new MethodExtractor(ctx);
                AnnotationExtractor annotationExtractor = new AnnotationExtractor(ctx);
                HierarchyExtractor hierarchyExtractor = new HierarchyExtractor(ctx);
                ReferenceExtractor referenceExtractor = new ReferenceExtractor(ctx);
                CallGraphExtractor callGraphExtractor = new CallGraphExtractor(ctx);
                FieldAccessExtractor fieldAccessExtractor = new FieldAccessExtractor(ctx);

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
                    typeExtractor.extract(cu, result);
                    fieldExtractor.extract(cu, result);
                    methodExtractor.extract(cu, result);
                    annotationExtractor.extract(cu, result);
                    hierarchyExtractor.extract(cu, result);
                    referenceExtractor.extract(cu, result);
                    callGraphExtractor.extract(cu, result);
                    fieldAccessExtractor.extract(cu, result);
                });

                pruneDanglingInternalEdges(result);
                new SemanticPostProcessor().process(result);
                store.write(result);
            }

            // Count nodes/edges per source file in this batch
            Map<String, int[]> perFile = new HashMap<>();
            for (Node n : result.nodes) {
                String f = n.sourceFile;
                if (f == null) continue;
                perFile.computeIfAbsent(f, k -> new int[2])[0]++;
            }
            for (Edge e : result.edges) {
                String f = e.sourceFile;
                if (f == null) continue;
                perFile.computeIfAbsent(f, k -> new int[2])[1]++;
            }

            String now = Instant.now().toString();
            List<FileCacheEntry> entries = new ArrayList<>();
            for (String rel : toReparse) {
                String hash = diskHashes.get(rel);
                if (hash == null) continue;
                int[] cnt = perFile.getOrDefault(rel, new int[]{0, 0});
                entries.add(new FileCacheEntry(rel, hash, FileCacheService.CURRENT_SCHEMA_VERSION,
                        now, cnt[0], cnt[1], 0, null));
            }
            if (!entries.isEmpty()) store.updateFileCache(entries);

            s.newNodes = result.nodes.size();
            s.newEdges = result.edges.size();

            // Re-derive file_dependencies and mark dependents stale.
            store.clearFileDependencies();
            store.deriveFileDependencies();
            List<String> changedForStale = new ArrayList<>(changedFiles);
            changedForStale.addAll(deletedFiles);
            if (!changedForStale.isEmpty()) store.markStaleDependents(changedForStale);

            c.commit();
        } catch (RuntimeException | SQLException e) {
            try { c.rollback(); } catch (SQLException ignore) {}
            throw new RuntimeException("Incremental index failed: " + e.getMessage(), e);
        } finally {
            try { c.setAutoCommit(priorAutoCommit); } catch (SQLException ignore) {}
        }
        return s;
    }

    private static int pruneDanglingInternalEdges(ExtractionResult r) {
        Set<String> known = new HashSet<>();
        for (Node n : r.nodes) known.add(n.id);
        int before = r.edges.size() + r.annotations.size();
        r.edges.removeIf(e -> !e.isExternal && (e.targetId == null || !known.contains(e.targetId)));
        r.edges.removeIf(e -> e.sourceId == null || !known.contains(e.sourceId));
        r.annotations.removeIf(a -> a.nodeId == null || !known.contains(a.nodeId));
        return before - r.edges.size() - r.annotations.size();
    }
}
