package com.anatomist.core;

import java.util.Locale;

/** Exit policy applied after an index or doctor health report is available. */
public enum HealthPolicy {
    NONE,
    INTEGRITY,
    COMPLETE;

    public static HealthPolicy resolve(boolean strictHealth, String supplied) {
        HealthPolicy explicit = parse(supplied);
        if (strictHealth) {
            if (explicit != null && explicit != COMPLETE) {
                throw new IllegalArgumentException(
                        "--strict-health is the complete-policy alias and cannot be combined with "
                                + explicit.name().toLowerCase(Locale.ROOT));
            }
            return COMPLETE;
        }
        return explicit == null ? NONE : explicit;
    }

    public static HealthPolicy parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "invalid --health-policy '" + value
                            + "' (expected none, integrity, or complete)");
        }
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
