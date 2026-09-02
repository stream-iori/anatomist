package com.anatomist.framework.lombok;

import java.util.Locale;

public enum LombokMode {
    OFF, AST;

    public static LombokMode parse(String value) {
        if (value == null || value.isBlank()) return OFF;
        try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("lombok mode must be off or ast: " + value);
        }
    }

    public String optionValue() { return name().toLowerCase(Locale.ROOT); }
}
