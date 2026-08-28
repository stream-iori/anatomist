package com.anatomist.config;

import java.nio.file.Path;

/** One selected configuration source. User and project files are never merged. */
public record LoadedConfig(ProjectConfig config, Source source, Path path) {
    public enum Source { DEFAULT, USER, PROJECT }

    public LoadedConfig {
        config = config == null ? new ProjectConfig() : config;
    }

    public String sourceName() {
        return source.name().toLowerCase(java.util.Locale.ROOT);
    }
}
