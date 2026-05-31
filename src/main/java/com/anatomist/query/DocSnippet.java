package com.anatomist.query;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocSnippet {
    public String title;
    public String path;
    public String snippet;
    public String docType;
}
