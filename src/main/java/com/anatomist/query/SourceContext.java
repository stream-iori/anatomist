package com.anatomist.query;

/** Exact, page-bounded source declaration view attached to a context result. */
public final class SourceContext {
    public String status;
    public String sourceFile;
    public String sourceRange;
    public Integer startLine;
    public Integer endLine;
    public Integer totalLines;
    public Integer offset;
    public Integer limit;
    public Boolean truncated;
    public String snippet;
    public String warningCode;
    public String warningMessage;

    public boolean fatal() {
        return "unavailable".equals(status) || "error".equals(status);
    }
}
