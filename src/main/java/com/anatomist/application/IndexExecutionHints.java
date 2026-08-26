package com.anatomist.application;

import com.anatomist.core.JavaParserFactory;
import com.anatomist.application.ProjectMetadata;
import com.anatomist.core.SourceRoot;
import com.anatomist.incremental.IncrementalSessionState;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Internal Watch-to-index hand-off. It is deliberately not a CLI contract. */
public record IndexExecutionHints(
        List<SourceRoot> sourceRoots,
        Set<String> candidateFiles,
        List<Path> springXmlFiles,
        JavaParserFactory.SessionCache parserSessions,
        ProjectMetadata.FingerprintCache fingerprintCache,
        IncrementalSessionState incrementalSession,
        boolean complete
) {
    public IndexExecutionHints {
        sourceRoots = sourceRoots == null ? List.of() : List.copyOf(sourceRoots);
        candidateFiles = candidateFiles == null ? Set.of() : Set.copyOf(candidateFiles);
        springXmlFiles = springXmlFiles == null ? List.of() : List.copyOf(springXmlFiles);
    }

    public boolean canUseFastPath() {
        return complete && !sourceRoots.isEmpty();
    }
}
