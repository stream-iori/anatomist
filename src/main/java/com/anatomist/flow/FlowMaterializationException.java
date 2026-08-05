package com.anatomist.flow;

/** A safe, structured refusal to mutate a progressive flow index. */
public final class FlowMaterializationException extends RuntimeException {
    private final String code;

    public FlowMaterializationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
