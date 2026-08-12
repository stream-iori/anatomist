package com.anatomist.query;

/** Stable query failure for an ambiguous, missing, or insufficiently exact selector. */
public final class SymbolResolutionException extends RuntimeException {

    private final String code;
    private final SymbolResolution resolution;

    private SymbolResolutionException(String code, String message,
                                      SymbolResolution resolution) {
        super(message);
        this.code = code;
        this.resolution = resolution;
    }

    public static SymbolResolutionException ambiguous(SymbolResolution resolution) {
        return new SymbolResolutionException(
                "SYMBOL_AMBIGUOUS",
                resolution.targetKind().name().toLowerCase() + " selector '"
                        + resolution.input() + "' matches multiple targets",
                resolution);
    }

    public static SymbolResolutionException notFound(SymbolResolution resolution) {
        return new SymbolResolutionException(
                "SYMBOL_NOT_FOUND",
                "no " + resolution.targetKind().name().toLowerCase()
                        + " matches selector '" + resolution.input() + "'",
                resolution);
    }

    public static SymbolResolutionException exactRequired(SymbolResolution resolution) {
        return new SymbolResolutionException(
                "SYMBOL_EXACT_REQUIRED",
                "a full exact " + resolution.targetKind().name().toLowerCase()
                        + " signature is required: '" + resolution.input() + "'",
                resolution);
    }

    public String code() {
        return code;
    }

    public SymbolResolution resolution() {
        return resolution;
    }
}
