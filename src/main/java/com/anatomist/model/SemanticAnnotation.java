package com.anatomist.model;

import com.anatomist.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SemanticAnnotation {
    public String nodeId;
    public Integer docId;
    public String category;
    public String businessLabel;
    public String businessDescription;
    public String domainContext;
    public String source;
    public String confidence;

    /** Snake-case JSON object → {@link SemanticAnnotation}. */
    public static SemanticAnnotation fromJson(Map<String, Object> tree) {
        SemanticAnnotation sa = new SemanticAnnotation();
        sa.nodeId               = (String) tree.get("node_id");
        Object docIdRaw         = tree.get("doc_id");
        sa.docId                = docIdRaw == null ? null : ((Number) docIdRaw).intValue();
        sa.category             = (String) tree.get("category");
        sa.businessLabel        = (String) tree.get("business_label");
        sa.businessDescription  = (String) tree.get("business_description");
        sa.domainContext        = (String) tree.get("domain_context");
        sa.source               = (String) tree.get("source");
        sa.confidence           = (String) tree.get("confidence");
        return sa;
    }

    /** Parse a snake-case JSON array into a list of {@link SemanticAnnotation}. */
    @SuppressWarnings("unchecked")
    public static List<SemanticAnnotation> listFromJson(String json) {
        Object root = Json.parseTree(json);
        if (!(root instanceof List<?>)) {
            throw new IllegalArgumentException(
                    "expected a JSON array; got " + (root == null ? "null" : root.getClass().getSimpleName()));
        }
        List<SemanticAnnotation> out = new ArrayList<>();
        for (Object element : (List<Object>) root) {
            if (!(element instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("array element is not an object: " + element);
            }
            out.add(fromJson((Map<String, Object>) element));
        }
        return out;
    }
}

