package com.anatomist.model;

public class ArchRole {
    public String nodeId;
    public String role;
    public String confidence;
    public String source;

    public ArchRole() {}

    public ArchRole(String nodeId, String role, String confidence, String source) {
        this.nodeId = nodeId;
        this.role = role;
        this.confidence = confidence;
        this.source = source;
    }
}
