package com.anatomist.query;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregate result for the {@code enrich} command. Either {@link #node} (single-node
 *  mode) or {@link #pkg} (package mode) is populated. */
public class EnrichResult {
    public NodeRow node;
    public String pkg;
    public List<NodeRow> members = new ArrayList<>();
    public List<Map<String, Object>> annotations = new ArrayList<>();
    public List<SemanticAnnotationRow> semanticAnnotations = new ArrayList<>();
    public List<EdgeRow> callees = new ArrayList<>();
    public List<Map<String, Object>> packageDeps = new ArrayList<>();
    public List<DocSnippet> relatedDocs = new ArrayList<>();
    public List<String> suggestedQueries = new ArrayList<>();

    public Map<String, Object> toStats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("members", members.size());
        s.put("annotations", annotations.size());
        s.put("semantic_annotations", semanticAnnotations.size());
        s.put("callees", callees.size());
        s.put("related_docs", relatedDocs.size());
        return s;
    }
}
