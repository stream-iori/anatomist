package com.anatomist.semantic;

import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.anatomist.model.SemanticAnnotation;

/**
 * Post-index semantic enrichment. Reads ExtractionResult.{nodes, annotations}
 * and fills ExtractionResult.semanticAnnotations with:
 *  - JAVADOC summary extraction → confidence=HIGH
 *
 * Pure Java; no IO, no SymbolSolver. Runs after extractors, before SqliteStore.write.
 */
public class SemanticPostProcessor {

    public void process(ExtractionResult result) {
        applyJavadocRules(result);
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
        int tag = findJavadocTag(javadoc);
        int cut = javadoc.length();
        if (blank >= 0) cut = Math.min(cut, blank);
        if (tag   >= 0) cut = Math.min(cut, tag);
        return javadoc.substring(0, cut).trim();
    }

    private static int findJavadocTag(String javadoc) {
        for (int i = 0; i < javadoc.length(); i++) {
            if (i > 0 && javadoc.charAt(i - 1) != '\n' && javadoc.charAt(i - 1) != '\r') {
                continue;
            }
            int tag = i;
            while (tag < javadoc.length()
                    && (javadoc.charAt(tag) == ' ' || javadoc.charAt(tag) == '\t')) {
                tag++;
            }
            if (tag + 1 < javadoc.length()
                    && javadoc.charAt(tag) == '@'
                    && isAsciiWord(javadoc.charAt(tag + 1))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isAsciiWord(char character) {
        return character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9'
                || character == '_';
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
