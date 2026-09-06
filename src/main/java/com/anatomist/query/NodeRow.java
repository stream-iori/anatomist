package com.anatomist.query;

import java.util.Map;

/** Tabular projection of a {@code nodes} row used by search / list results. */
public class NodeRow {
    public String id;
    public String symbolId;
    public String domain;
    public String language;
    public String providerId;
    public String entityKind;
    public String languageKind;
    public String namespace;
    public String label;
    public String kind;
    public String qualifiedName;
    public String sourceFile;
    public String sourceLocation;
    public Integer beginLine;
    public Integer beginColumn;
    public Integer endLine;
    public Integer endColumn;
    public Integer sourceOrdinal;
    public String module;
    public String scope;
    public String javadoc;
    public String producerId;
    /** Present only for declarations synthesized by an extension. */
    public Map<String, Object> syntheticOrigin;
    /** Signature-level Lombok coverage attached to source type/field nodes. */
    public Map<String, Object> lombok;
    /** True for a query-only aggregation of external edges, never a source node. */
    public Boolean externalTarget;
    public Long externalEdgeCount;
    public Map<String, Long> relationCounts;
    public Map<String, Long> resolutionCounts;
    public Map<String, Long> confidenceCounts;
    public Map<String, Long> producerCounts;
}
