package com.anatomist.store;

import com.anatomist.model.Annotation;
import com.anatomist.model.Edge;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.model.Node;
import com.anatomist.model.SemanticAnnotation;

import java.util.List;

public interface IndexWriter extends AutoCloseable {

    void initSchema();

    boolean schemaExists();

    void clearAllData();

    void writeNodes(List<Node> nodes);

    void writeEdgesBatched(List<Edge> edges, int batchSize);

    void writeAnnotationsBatched(List<Annotation> annotations, List<SemanticAnnotation> semanticAnnotations, int batchSize);

    void updateFileCache(List<FileCacheEntry> entries);

    void upsertProjectMeta(String key, String value);

    void clearFileDependencies();

    void deriveFileDependencies();

    void deleteBySourceFiles(List<String> sourceFiles);

    void runAnalyze();

    @Override
    void close();
}
