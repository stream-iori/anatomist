package com.anatomist.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExtractionResult {
    public final List<Node> nodes = new ArrayList<>();
    public final List<Edge> edges = new ArrayList<>();
    public final List<Annotation> annotations = new ArrayList<>();
    public final List<SemanticAnnotation> semanticAnnotations = new ArrayList<>();
    public final Map<String, Object> stats = new HashMap<>();
}
