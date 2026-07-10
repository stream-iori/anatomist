package com.anatomist.core;

import java.nio.file.Path;
import java.util.List;

/** CLI-independent identity and location inputs for an index run. */
public record IndexRequest(
        Path projectPath,
        String projectSource,
        List<String> sourceRootSpecs
) {
    public IndexRequest {
        sourceRootSpecs = sourceRootSpecs == null ? List.of() : List.copyOf(sourceRootSpecs);
    }
}
