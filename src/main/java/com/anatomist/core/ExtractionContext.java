package com.anatomist.core;

import org.eclipse.jdt.core.dom.ITypeBinding;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Shared, per-index-run context handed to every Extractor.
 *
 * <p>For the Phase 1 MVP this carries only what TypeExtractor / MethodExtractor
 * need; future extractors will extend it.
 */
public class ExtractionContext {

    private final Path projectRoot;
    private final List<Path> sourcePaths;
    private final NodeIdGenerator idGenerator;
    private final String module;
    private final String scope;

    public ExtractionContext(Path projectRoot,
                             List<Path> sourcePaths,
                             NodeIdGenerator idGenerator,
                             String module,
                             String scope) {
        this.projectRoot = projectRoot;
        this.sourcePaths = sourcePaths == null ? Collections.emptyList() : List.copyOf(sourcePaths);
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.module = module;
        this.scope = scope == null ? "MAIN" : scope;
    }

    public Path projectRoot() { return projectRoot; }
    public List<Path> sourcePaths() { return sourcePaths; }
    public NodeIdGenerator idGenerator() { return idGenerator; }
    public String module() { return module; }
    public String scope() { return scope; }

    public boolean isProjectInternal(ITypeBinding binding) {
        if (binding == null) return false;
        // Primary signal: JDT marks any binding it parsed from source as "from
        // source". This is true both for test cases that parse in-memory
        // snippets (no sourcePaths configured) and for real index runs (the
        // file lives under sourcePaths). Anything from a classpath jar
        // returns false here, which is exactly the External Dependency case.
        if (binding.isFromSource()) return true;
        // Fallback: explicit source path check for binary bindings that JDT
        // somehow still surfaces with a source path (rare).
        try {
            String path = binding.getJavaElement() == null
                    ? null
                    : binding.getJavaElement().getPath().toOSString();
            if (path == null) return false;
            for (Path src : sourcePaths) {
                if (path.startsWith(src.toString())) return true;
            }
            if (projectRoot != null && path.startsWith(projectRoot.toString())) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
