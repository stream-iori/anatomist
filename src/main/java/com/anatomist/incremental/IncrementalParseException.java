package com.anatomist.incremental;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Retryable failure raised when changed Java sources are temporarily unparsable. */
public final class IncrementalParseException extends IllegalStateException {
    private final List<String> sourceFiles;
    private final Map<String, List<String>> diagnostics;

    public IncrementalParseException(List<String> sourceFiles,
                                     Map<String, List<String>> diagnostics) {
        super(message(sourceFiles, diagnostics));
        this.sourceFiles = List.copyOf(sourceFiles);
        Map<String, List<String>> copy = new LinkedHashMap<>();
        diagnostics.forEach((file, messages) -> copy.put(file, List.copyOf(messages)));
        this.diagnostics = Collections.unmodifiableMap(copy);
    }

    public List<String> sourceFiles() {
        return sourceFiles;
    }

    public Map<String, List<String>> diagnostics() {
        return diagnostics;
    }

    public String firstDiagnostic() {
        for (String file : sourceFiles) {
            List<String> messages = diagnostics.get(file);
            if (messages != null && !messages.isEmpty()) return messages.get(0);
        }
        return "parser produced no compilation unit";
    }

    private static String message(List<String> sourceFiles,
                                  Map<String, List<String>> diagnostics) {
        List<String> ordered = new ArrayList<>(sourceFiles);
        String first = "parser produced no compilation unit";
        for (String file : ordered) {
            List<String> messages = diagnostics.get(file);
            if (messages != null && !messages.isEmpty()) {
                first = messages.get(0);
                break;
            }
        }
        return "incremental source parse failed for " + ordered + ": " + first;
    }
}
