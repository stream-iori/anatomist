package com.anatomist.query;

import java.util.Map;

/** Tabular projection of a {@code nodes} row used by search / list results. */
public class NodeRow {
    public String id;
    public String symbolId;
    public String label;
    public String kind;
    public String qualifiedName;
    public String sourceFile;
    public String sourceLocation;
    public String module;
    public String scope;
    public String javadoc;
    /** True for a query-only aggregation of external edges, never a source node. */
    public Boolean externalTarget;
    public Long externalEdgeCount;
    public Map<String, Long> relationCounts;
    public Map<String, Long> resolutionCounts;
    public Map<String, Long> confidenceCounts;
}
