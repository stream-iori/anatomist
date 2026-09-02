package com.anatomist.model;

/** Applies source and producer ownership to a newly emitted slice of facts. */
public final class FactOrigin {
    private FactOrigin() {}

    public static Cursor cursor(ExtractionResult result) {
        return new Cursor(result.nodes.size(), result.edges.size(), result.annotations.size(),
                result.semanticAnnotations.size(), result.declarations.size());
    }

    public static void stamp(ExtractionResult result, Cursor start,
                             String sourceFile, String producerId) {
        for (int i = start.nodes(); i < result.nodes.size(); i++) {
            Node fact = result.nodes.get(i);
            if (fact.sourceFile == null) fact.sourceFile = sourceFile;
            fact.producerId = producerId;
        }
        for (int i = start.edges(); i < result.edges.size(); i++) {
            Edge fact = result.edges.get(i);
            if (fact.sourceFile == null) fact.sourceFile = sourceFile;
            fact.producerId = producerId;
        }
        for (int i = start.annotations(); i < result.annotations.size(); i++) {
            Annotation fact = result.annotations.get(i);
            if (fact.sourceFile == null) fact.sourceFile = sourceFile;
            fact.producerId = producerId;
        }
        for (int i = start.semanticAnnotations(); i < result.semanticAnnotations.size(); i++) {
            SemanticAnnotation fact = result.semanticAnnotations.get(i);
            if (fact.sourceFile == null) fact.sourceFile = sourceFile;
            fact.producerId = producerId;
        }
        for (int i = start.declarations(); i < result.declarations.size(); i++) {
            Declaration fact = result.declarations.get(i);
            if (fact.sourceFile == null) fact.sourceFile = sourceFile;
            fact.producerId = producerId;
        }
    }

    public record Cursor(int nodes, int edges, int annotations,
                         int semanticAnnotations, int declarations) {}
}
