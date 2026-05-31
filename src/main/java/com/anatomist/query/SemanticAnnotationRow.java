package com.anatomist.query;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SemanticAnnotationRow {
    public String category;
    public String businessLabel;
    public String businessDescription;
    public String domainContext;
    public String source;
    public String confidence;
}
