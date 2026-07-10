package com.anatomist.model;

public class Node {
    public String id;
    public String symbolId;
    public String label;
    public String kind;
    public String qualifiedName;
    public String pkg;
    public String sourceFile;
    public String sourceLocation;
    public String module;
    public String scope;
    public String javadoc;
    public String metadata;

    public Node() {}

    public Node sourceFile(String sourceFile) { this.sourceFile = sourceFile; return this; }
    public Node sourceLocation(String loc) { this.sourceLocation = loc; return this; }
    public Node module(String module) { this.module = module; return this; }
    public Node scope(String scope) { this.scope = scope; return this; }
    public Node javadoc(String javadoc) { this.javadoc = javadoc; return this; }
    public Node metadata(String metadata) { this.metadata = metadata; return this; }
}
