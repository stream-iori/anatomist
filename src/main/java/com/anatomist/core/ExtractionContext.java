package com.anatomist.core;

import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserAnnotationDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserAnonymousClassDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserEnumDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserInterfaceDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared, per-index-run context handed to every Extractor.
 *
 * <p>Phase 1 carries: project root, source paths (for path-based fallbacks),
 * the Node ID generator, current module / scope, and an unresolved-symbol
 * counter.</p>
 */
public class ExtractionContext {

    private final Path projectRoot;
    private final List<Path> sourcePaths;
    private final NodeIdGenerator idGenerator;
    private final String module;
    private final String scope;
    private final AtomicLong unresolved = new AtomicLong();

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

    public long unresolvedCount() { return unresolved.get(); }
    public void incrementUnresolved() { unresolved.incrementAndGet(); }

    /**
     * A symbol is "project internal" iff the SymbolSolver returned it through
     * a {@code JavaParserTypeSolver} — that solver is the one we register for
     * each source root. Reflection / Jar solvers yield other concrete classes.
     */
    public boolean isProjectInternal(ResolvedTypeDeclaration decl) {
        if (decl == null) return false;
        return decl instanceof JavaParserClassDeclaration
                || decl instanceof JavaParserInterfaceDeclaration
                || decl instanceof JavaParserEnumDeclaration
                || decl instanceof JavaParserAnnotationDeclaration
                || decl instanceof JavaParserAnonymousClassDeclaration;
    }

    public boolean isProjectInternal(ResolvedReferenceTypeDeclaration decl) {
        return isProjectInternal((ResolvedTypeDeclaration) decl);
    }
}
