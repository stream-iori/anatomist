package com.anatomist.application;

import com.anatomist.config.ProjectConfig;
import com.anatomist.config.LoadedConfig;
import com.anatomist.core.JavaVersionDetection;
import com.anatomist.core.ScanPolicy;
import com.anatomist.core.SourceRoot;
import com.anatomist.core.SourceScope;
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
        boolean implicitTaint,
        LoadedConfig loadedConfig,
        ScanPolicy scanPolicy,
        List<SourceScope> scanScopes
) {
    public boolean dataflow() {
        return flowProfile != null && flowProfile.enabled();
    }
}
