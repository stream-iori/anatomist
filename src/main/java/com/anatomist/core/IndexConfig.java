package com.anatomist.core;

import com.anatomist.config.ProjectConfig;
import com.anatomist.flow.FlowProfile;

import java.nio.file.Path;
import java.util.List;

public record IndexConfig(
        Path projectRoot,
        List<Path> sourcePaths,
        List<Path> classpathEntries,
        List<Path> sourceFiles,
        int javaVersion,
        boolean springXml,
        ProjectConfig config,
        Path dbPath,
        String classpathOverride,
        boolean noClasspath,
        boolean debug,
        List<SourceRoot> sourceRoots,
        boolean strictHealth,
        JavaVersionDetection javaVersionDetection,
        FlowProfile flowProfile,
        boolean implicitTaint
) {
    public boolean dataflow() {
        return flowProfile != null && flowProfile.enabled();
    }
}
