package com.anatomist.core;

import java.nio.file.Path;

public record SourceRoot(Path path, String module, SourceScope scope) {
    public SourceRoot {
        path = path.toAbsolutePath().normalize();
        module = module == null || module.isBlank() ? "." : module.replace('\\', '/');
        scope = scope == null ? SourceScope.MAIN : scope;
    }
}
