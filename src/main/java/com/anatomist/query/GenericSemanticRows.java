package com.anatomist.query;

/** Storage-independent source-backed site used by generic semantic operations. */
public final class GenericSemanticRows {
    private GenericSemanticRows() {}

    public record Site(String source, String target, String externalTarget,
                       String relation, String sourceFile, Integer beginLine,
                       Integer beginColumn, Integer endLine, Integer endColumn,
                       Integer ordinal, String context, String producerId,
                       String confidence, String resolution) {}
}
