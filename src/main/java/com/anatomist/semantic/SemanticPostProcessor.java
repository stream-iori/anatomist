package com.anatomist.semantic;

import com.anatomist.model.Annotation;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.anatomist.model.SemanticAnnotation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-index semantic enrichment. Reads ExtractionResult.{nodes, annotations}
 * and fills ExtractionResult.semanticAnnotations with:
 *  - CONVENTION rules (6 annotation + 5 naming) → confidence=MEDIUM
 *  - JAVADOC summary extraction → confidence=HIGH
 *
 * Pure Java; no IO, no SymbolSolver. Runs after extractors, before SqliteStore.write.
 */
public class SemanticPostProcessor {

    static final List<ConventionRule> RULES = List.of(
            // Annotation rules (BR-003): match annotations.annotation_fqn on the holding node.
            ConventionRule.annotation("org.springframework.stereotype.Service",                       "BUSINESS_SERVICE"),
            ConventionRule.annotation("org.springframework.stereotype.Repository",                    "DATA_ACCESS"),
            ConventionRule.annotation("org.springframework.web.bind.annotation.RestController",       "API_ENDPOINT"),
            ConventionRule.annotation("org.springframework.stereotype.Controller",                    "API_ENDPOINT"),
            ConventionRule.annotation("jakarta.persistence.Entity",                                   "PERSISTENCE_ENTITY"),
            ConventionRule.annotation("org.springframework.transaction.annotation.Transactional",     "TRANSACTION_BOUNDARY"),
            ConventionRule.annotation("org.springframework.stereotype.Component",                     "FRAMEWORK_COMPONENT"),

            // Naming rules (BR-004): match nodes.label endsWith; only for CLASS/INTERFACE/ENUM/RECORD.
            ConventionRule.naming("Service",       "BUSINESS_SERVICE"),
            ConventionRule.naming("DTO",           "DTO"),
            ConventionRule.naming("Request",       "DTO"),
            ConventionRule.naming("Response",      "DTO"),
            ConventionRule.naming("Repository",    "DATA_ACCESS"),
            ConventionRule.naming("Dao",           "DATA_ACCESS"),
            ConventionRule.naming("Controller",    "API_ENDPOINT"),
            ConventionRule.naming("Config",        "CONFIGURATION"),
            ConventionRule.naming("Configuration", "CONFIGURATION")
    );

    public void process(ExtractionResult result) {
        applyConventionRules(result);
        applyJavadocRules(result);
    }

    private static final Set<String> TYPE_KINDS = Set.of("CLASS", "INTERFACE", "ENUM", "RECORD");
    private static final Pattern JAVADOC_TAG = Pattern.compile("(?m)^\\s*@\\w+");

    private void applyConventionRules(ExtractionResult result) {
        Map<String, Node> byId = new HashMap<>();
        for (Node n : result.nodes) byId.put(n.id, n);

        Map<String, Set<String>> annByNode = new HashMap<>();
        for (Annotation a : result.annotations) {
            if (a.nodeId == null || a.annotationFqn == null) continue;
            annByNode.computeIfAbsent(a.nodeId, k -> new HashSet<>()).add(a.annotationFqn);
        }

        for (Node n : result.nodes) {
            Set<String> anns = annByNode.getOrDefault(n.id, Set.of());
            boolean isType = TYPE_KINDS.contains(n.kind);
            for (ConventionRule rule : RULES) {
                if (rule.isAnnotation()) {
                    if (anns.contains(rule.annotationFqn)) {
                        result.semanticAnnotations.add(write(n.id, rule.category, "CONVENTION", "MEDIUM"));
                    }
                } else {
                    if (!isType) continue;
                    if (n.label != null && n.label.endsWith(rule.labelSuffix)) {
                        result.semanticAnnotations.add(write(n.id, rule.category, "CONVENTION", "MEDIUM"));
                    }
                }
            }
        }
    }

    private void applyJavadocRules(ExtractionResult result) {
        for (Node n : result.nodes) {
            if (n.javadoc == null || n.javadoc.isBlank()) continue;
            String summary = firstBlankLineOrTag(n.javadoc);
            if (summary.isEmpty()) continue;
            SemanticAnnotation sa = write(n.id, null, "JAVADOC", "HIGH");
            sa.businessDescription = summary;
            result.semanticAnnotations.add(sa);
        }
    }

    /**
     * Take the part of {@code javadoc} before the first blank line OR first
     * javadoc tag ({@code @param} / {@code @return} / ...), then trim.
     */
    static String firstBlankLineOrTag(String javadoc) {
        if (javadoc == null) return "";
        int blank = javadoc.indexOf("\n\n");
        Matcher m = JAVADOC_TAG.matcher(javadoc);
        int tag = m.find() ? m.start() : -1;
        int cut = javadoc.length();
        if (blank >= 0) cut = Math.min(cut, blank);
        if (tag   >= 0) cut = Math.min(cut, tag);
        return javadoc.substring(0, cut).trim();
    }

    private static SemanticAnnotation write(String nodeId, String category, String source, String confidence) {
        SemanticAnnotation sa = new SemanticAnnotation();
        sa.nodeId = nodeId;
        sa.category = category;
        sa.source = source;
        sa.confidence = confidence;
        return sa;
    }
}
