package com.anatomist.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExtractionResult {
    public static final int FLUSH_THRESHOLD = 50_000;

    public final List<Node> nodes = new ArrayList<>();
    public final List<Edge> edges = new ArrayList<>();
    public final List<Annotation> annotations = new ArrayList<>();
    public final List<SemanticAnnotation> semanticAnnotations = new ArrayList<>();
    public final Map<String, Object> stats = new HashMap<>();

    /** Number of graph facts currently retained by this batch. */
    public int factCount() {
        return nodes.size() + edges.size() + annotations.size() + semanticAnnotations.size();
    }

    /** Release all graph facts after a staging flush while preserving aggregate stats. */
    public void clearFacts() {
        nodes.clear();
        edges.clear();
        annotations.clear();
        semanticAnnotations.clear();
    }
}
