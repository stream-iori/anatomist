package com.anatomist.model;

public class Edge {
    public String sourceId;
    public String targetId;
    public String externalTargetFqn;
    public String relation;
    public String callKind;
    public String confidence;
    public String context;
    public boolean isExternal;
    public String sourceFile;
    public String sourceLocation;
    public String metadata;

    public Edge() {}

    public static Edge call(String sourceId, String targetId, String callKind, String sourceLocation) {
        Edge e = new Edge();
        e.sourceId = sourceId;
        e.targetId = targetId;
        e.relation = "CALLS";
        e.callKind = callKind;
        e.confidence = "EXTRACTED";
        e.sourceLocation = sourceLocation;
        e.isExternal = false;
        return e;
    }

    public static Edge externalCall(String sourceId, String externalTargetFqn, String callKind, String sourceLocation) {
        Edge e = new Edge();
        e.sourceId = sourceId;
        e.externalTargetFqn = externalTargetFqn;
        e.relation = "CALLS";
        e.callKind = callKind;
        e.confidence = "EXTRACTED";
        e.sourceLocation = sourceLocation;
        e.isExternal = true;
        return e;
    }

    public static Edge reference(String sourceId, String targetId, boolean isExternal) {
        Edge e = new Edge();
        e.sourceId = sourceId;
        e.targetId = targetId;
        e.relation = "REFERENCES";
        e.confidence = "EXTRACTED";
        e.isExternal = isExternal;
        return e;
    }

    public Edge context(String context) { this.context = context; return this; }
    public Edge sourceFile(String sourceFile) { this.sourceFile = sourceFile; return this; }
    public Edge metadata(String metadata) { this.metadata = metadata; return this; }
}
