package com.anatomist.core;

import com.anatomist.core.logging.AnatomistLog;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserAnnotationDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserAnonymousClassDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserEnumDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserInterfaceDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserRecordDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.UnsolvedSymbolException;

import com.anatomist.config.ProjectConfig;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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
    private final ProjectConfig projectConfig;
    private final ResolutionTracker resolutionTracker;

    /** Opt-in diagnostic: when {@code -Danatomist.sampleUnresolved=true}, every
     *  unresolved symbol's name is aggregated by frequency so an index run can
     *  report what's actually failing (project-internal vs third-party vs JDK).
     *  Bounded so a pathological project can't OOM the map. Null when disabled
     *  → zero overhead on the hot path. */
    private static final boolean SAMPLE = Boolean.getBoolean("anatomist.sampleUnresolved");
    private static final int MAX_SAMPLE_KEYS = 50_000;
    private final ConcurrentHashMap<String, Long> unresolvedSamples =
            SAMPLE ? new ConcurrentHashMap<>() : null;

    public ExtractionContext(Path projectRoot,
                             List<Path> sourcePaths,
                             NodeIdGenerator idGenerator,
                             String module,
                             String scope) {
        this(projectRoot, sourcePaths, idGenerator, module, scope, new ProjectConfig());
    }

    public ExtractionContext(Path projectRoot,
                             List<Path> sourcePaths,
                             NodeIdGenerator idGenerator,
                             String module,
                             String scope,
                             ProjectConfig projectConfig) {
        this.projectRoot = projectRoot;
        this.sourcePaths = sourcePaths == null ? Collections.emptyList() : List.copyOf(sourcePaths);
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.module = module;
        this.scope = scope == null ? "MAIN" : scope;
        this.projectConfig = projectConfig != null ? projectConfig : new ProjectConfig();
        this.resolutionTracker = new ResolutionTracker(projectRoot, this.sourcePaths);
    }

    public Path projectRoot() { return projectRoot; }
    public List<Path> sourcePaths() { return sourcePaths; }
    public NodeIdGenerator idGenerator() { return idGenerator; }
    public String module() { return module; }
    public String scope() { return scope; }
    public ProjectConfig projectConfig() { return projectConfig; }

    public boolean isExternalExcluded(String fqn) {
        return projectConfig.isExternalExcluded(fqn);
    }

    public long unresolvedCount() { return resolutionTracker.unresolvedCount(); }
    public void incrementUnresolved() { incrementUnresolved(null); }

    /** Count one unresolved symbol; when sampling is enabled and {@code cause}
     *  carries a symbol name (e.g. {@link UnsolvedSymbolException}), aggregate it.
     *  Under {@code --debug} each failure is also logged verbatim. */
    public void incrementUnresolved(Throwable cause) {
        resolutionTracker.record(cause);
        if (cause != null && AnatomistLog.isDebugEnabled()) {
            AnatomistLog.debug("unresolved: " + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage());
        }
        if (unresolvedSamples == null || cause == null) return;
        String key = sampleKey(cause);
        if (key == null) return;
        // Don't grow unboundedly: stop adding new keys past the cap, but keep
        // counting hits on keys we've already seen.
        if (unresolvedSamples.size() >= MAX_SAMPLE_KEYS && !unresolvedSamples.containsKey(key)) return;
        unresolvedSamples.merge(key, 1L, Long::sum);
    }

    public boolean samplingEnabled() { return unresolvedSamples != null; }

    /** Snapshot of the unresolved-symbol frequency map (empty when sampling off). */
    public Map<String, Long> unresolvedSamples() {
        return unresolvedSamples == null ? Map.of() : Map.copyOf(unresolvedSamples);
    }

    public void enterFile(com.github.javaparser.ast.CompilationUnit unit) {
        resolutionTracker.enterFile(unit);
    }

    public void enterResolutionPhase(String phase) {
        resolutionTracker.enterPhase(phase);
    }

    public ResolutionSummary resolutionSummary(boolean noClasspath) {
        return resolutionTracker.snapshot(noClasspath);
    }

    static String sampleKey(Throwable t) {
        if (t == null) return "[unknown]";
        String name = null;
        if (t instanceof UnsolvedSymbolException u) {
            name = u.getName();
        }
        if (name == null) {
            String msg = t.getMessage();
            if (msg != null) {
                int idx = msg.lastIndexOf(" : ");
                if (idx >= 0) name = msg.substring(idx + 3);
            }
        }
        if (name == null) return "[" + t.getClass().getSimpleName() + "]";
        name = name.trim();
        return name.isEmpty() ? "[" + t.getClass().getSimpleName() + "]" : name;
    }

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
                || decl instanceof JavaParserRecordDeclaration
                || decl instanceof JavaParserAnnotationDeclaration
                || decl instanceof JavaParserAnonymousClassDeclaration;
    }

    public boolean isProjectInternal(ResolvedReferenceTypeDeclaration decl) {
        return isProjectInternal((ResolvedTypeDeclaration) decl);
    }
}
