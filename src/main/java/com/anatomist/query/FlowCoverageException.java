package com.anatomist.query;

public final class FlowCoverageException extends RuntimeException {
    private final String code;

    public FlowCoverageException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
