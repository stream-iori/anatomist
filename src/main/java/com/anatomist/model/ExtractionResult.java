package com.anatomist.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class ExtractionResult {
    public static final int FLUSH_THRESHOLD = 50_000;

    public final List<Node> nodes = new ArrayList<>();
    public final List<Edge> edges = new ArrayList<>();
    public final List<Annotation> annotations = new ArrayList<>();
    public final List<SemanticAnnotation> semanticAnnotations = new ArrayList<>();
    public final Map<String, Object> stats = new HashMap<>();

    private Consumer<List<Node>> nodeFlusher;
    private int nodesFlushedSoFar;

    public void setNodeFlusher(Consumer<List<Node>> flusher) {
        this.nodeFlusher = flusher;
    }

    public void flushNodesIfNeeded() {
        if (nodeFlusher != null && nodes.size() - nodesFlushedSoFar >= FLUSH_THRESHOLD) {
            List<Node> batch = new ArrayList<>(nodes.subList(nodesFlushedSoFar, nodes.size()));
            nodeFlusher.accept(batch);
            nodesFlushedSoFar = nodes.size();
        }
    }

    public void flushRemainingNodes() {
        if (nodeFlusher != null && nodesFlushedSoFar < nodes.size()) {
            List<Node> batch = new ArrayList<>(nodes.subList(nodesFlushedSoFar, nodes.size()));
            nodeFlusher.accept(batch);
            nodesFlushedSoFar = nodes.size();
        }
    }
}
