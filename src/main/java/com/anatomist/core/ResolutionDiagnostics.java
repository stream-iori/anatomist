package com.anatomist.core;

import java.util.Set;

/** Shared taxonomy for persisted symbol-resolution diagnostics. */
public final class ResolutionDiagnostics {

    public static final Set<String> REASON_CODES = Set.of(
            "INTERNAL_SYMBOL_MISSING",
            "THIRDPARTY_SYMBOL_MISSING",
            "JDK_SYMBOL_MISMATCH",
            "METHOD_NOT_FOUND",
            "FIELD_NOT_FOUND",
            "GENERIC_INFERENCE_FAILED",
            "AMBIGUOUS_OVERLOAD",
            "UNSUPPORTED_RESOLUTION",
            "OTHER_INFERENCE",
            "DIAGNOSTIC_LIMIT_REACHED");

    public static final Set<String> CLASSPATH_CODES = Set.of(
            "CLASSPATH_PARTIAL", "CLASSPATH_UNAVAILABLE");

    private ResolutionDiagnostics() {}

    public static boolean isReasonCode(String code) {
        return code != null && REASON_CODES.contains(code);
    }

    public static boolean blocksComplete(String code) {
        return isReasonCode(code) || CLASSPATH_CODES.contains(code)
                || "DIAGNOSTIC_STORAGE_TRUNCATED".equals(code);
    }
}
