package com.anatomist.core;

import java.nio.file.Path;
import java.util.List;

/** Static build-file evidence used to select the JavaParser language level. */
public record JavaVersionDetection(
        int version,
        Source source,
        Path evidenceFile,
        String evidenceExpression,
        List<IndexDiagnostic> diagnostics
) {
    public enum Source { CLI, CONFIG, MAVEN, GRADLE, FALLBACK, UNKNOWN }

    public JavaVersionDetection {
        source = source == null ? Source.UNKNOWN : source;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public boolean found() {
        return version > 0;
    }

    public boolean supported() {
        return version >= 8 && version <= 17;
    }

    public static JavaVersionDetection unknown(List<IndexDiagnostic> diagnostics) {
        return new JavaVersionDetection(-1, Source.UNKNOWN, null, null, diagnostics);
    }
}
