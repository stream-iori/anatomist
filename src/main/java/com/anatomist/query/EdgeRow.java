package com.anatomist.query;


/** Tabular projection of an {@code edges} row, optionally joined with the
 *  target node for human-readable labels. */
public class EdgeRow {
    public String source;
    public String sourceLabel;
    public String target;
    public String targetLabel;
    public String targetQualifiedName;
    public String externalTargetFqn;
    public String relation;
    public String callKind;
    public Boolean isExternal;
    public Integer depth;
    public String sourceFile;
    public String sourceLocation;
}
