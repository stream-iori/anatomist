package com.anatomist.query;


/** Tabular projection of an {@code edges} row, optionally joined with the
 *  target node for human-readable labels. */
public class EdgeRow {
    public String source;
    public String sourceLabel;
    public String sourceSymbolId;
    public String sourceModule;
    public String sourceScope;
    public String target;
    public String targetLabel;
    public String targetQualifiedName;
    public String targetSymbolId;
    public String targetModule;
    public String targetScope;
    public String externalTargetFqn;
    public String relation;
    public String callKind;
    public String confidence;
    public Boolean isExternal;
    public Integer depth;
    public String sourceFile;
    public String sourceLocation;
    public SourceWindow sourceWindow;
    public String context;
    public String metadata;
    /** P0: when this edge was synthesized by following into an anonymous-class /
     *  lambda body defined inside {@link #source}, the id of that body the call
     *  physically originated from. Null for direct edges. */
    public String via;
}
