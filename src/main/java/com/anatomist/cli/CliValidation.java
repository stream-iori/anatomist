package com.anatomist.cli;

import com.anatomist.model.GraphConstants;

import java.util.Locale;
import java.util.Set;

/** Shared validation for public CLI values that must never silently fall back. */
final class CliValidation {

    private static final Set<String> SCOPES = Set.of("MAIN", "TEST", "GENERATED", "ALL");
    private static final Set<String> KINDS = Set.of(
            GraphConstants.Kind.ANNOTATION,
            GraphConstants.Kind.ANONYMOUS_CLASS,
            GraphConstants.Kind.BEAN,
            GraphConstants.Kind.CLASS,
            GraphConstants.Kind.CONSTRUCTOR,
            GraphConstants.Kind.ENUM,
            GraphConstants.Kind.EXTERNAL_CLASS,
            GraphConstants.Kind.FIELD,
            GraphConstants.Kind.INTERFACE,
            GraphConstants.Kind.LAMBDA,
            GraphConstants.Kind.METHOD,
            GraphConstants.Kind.METHOD_REF,
            GraphConstants.Kind.RECORD,
            GraphConstants.Kind.ROUTE,
            GraphConstants.Kind.XML_CONSTRUCTOR_ARG,
            GraphConstants.Kind.XML_ENTRY,
            GraphConstants.Kind.XML_IDREF,
            GraphConstants.Kind.XML_LIST,
            GraphConstants.Kind.XML_MAP,
            GraphConstants.Kind.XML_NULL,
            GraphConstants.Kind.XML_PROPERTY,
            GraphConstants.Kind.XML_REF,
            GraphConstants.Kind.XML_VALUE);

    private CliValidation() {}

    static String scope(String value, boolean allowAll) {
        String normalized = value == null || value.isBlank()
                ? "MAIN" : value.toUpperCase(Locale.ROOT);
        if (!SCOPES.contains(normalized) || (!allowAll && "ALL".equals(normalized))) {
            throw new IllegalArgumentException("--scope must be "
                    + (allowAll ? "MAIN, TEST, GENERATED, or ALL" : "MAIN, TEST, or GENERATED")
                    + "; got " + value);
        }
        return normalized;
    }

    static String choice(String option, String value, String... allowed) {
        if (value == null) return null;
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String candidate : allowed) {
            if (candidate.equals(normalized)) return normalized;
        }
        throw new IllegalArgumentException(option + " must be "
                + String.join(" or ", allowed) + "; got " + value);
    }

    static String kind(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!KINDS.contains(normalized)) {
            throw new IllegalArgumentException("--kind is not a supported node kind: " + value);
        }
        return normalized;
    }

    static void nonNegative(String option, int value) {
        if (value < 0) throw new IllegalArgumentException(option + " must be >= 0; got " + value);
    }

    static void positive(String option, int value) {
        if (value <= 0) throw new IllegalArgumentException(option + " must be > 0; got " + value);
    }

    static int emit(IllegalArgumentException failure) {
        System.err.println("ERROR: " + failure.getMessage());
        return 2;
    }
}
