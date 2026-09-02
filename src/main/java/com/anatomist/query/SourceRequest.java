package com.anatomist.query;

public record SourceRequest(int limit, int offset) {
    public SourceRequest {
        if (limit <= 0 || limit > 1000) throw new IllegalArgumentException("source limit must be 1..1000");
        if (offset < 0) throw new IllegalArgumentException("source offset must be >= 0");
    }
}
