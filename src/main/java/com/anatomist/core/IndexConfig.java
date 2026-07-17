package com.anatomist.core;

import com.anatomist.config.ProjectConfig;

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
        boolean dataflow,
        boolean implicitTaint
) {}
