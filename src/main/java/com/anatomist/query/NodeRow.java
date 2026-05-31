package com.anatomist.query;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Tabular projection of a {@code nodes} row used by search / list results. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NodeRow {
    public String id;
    public String label;
    public String kind;
    public String qualifiedName;
    public String sourceFile;
    public String sourceLocation;
    public String module;
}
