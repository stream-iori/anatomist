package com.anatomist.core;

import com.anatomist.core.asmsolver.AsmTypeSolver;
import com.anatomist.core.asmsolver.ClasspathClassFileSource;
import com.anatomist.core.logging.AnatomistLog;
import com.anatomist.core.nativeimage.EmbeddedJdkTypeSolver;
import com.anatomist.core.nativeimage.JdkTypeCatalog;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.cache.Cache;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.cache.GuavaCache;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.utils.SourceRoot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import com.google.common.cache.CacheBuilder;

/**
 * Builds a configured {@link JavaParser} (with {@link JavaSymbolSolver}) and
 * drives batched source-root parsing.
 *
 * <p>Builds a {@link JavaParser} configured with a {@link JavaSymbolSolver}
 * over a {@link CombinedTypeSolver} and drives batched source-root parsing:</p>
 * <ul>
 *   <li>Source roots → {@link JavaParserTypeSolver}</li>
 *   <li>Dependency jars → {@link AsmTypeSolver} (native-image compatible
 *       ASM-based jar reader, no class loading)</li>
 *   <li>JDK classes → {@link ReflectionTypeSolver} (optional, with a version
 *       compatibility guard to avoid leaking the running JDK's API surface
 *       into analyses of older targets)</li>
 * </ul>
 */
public class JavaParserFactory {

    private static final int FULL_SOURCE_CACHE_SIZE = 256;
    private static final int WATCH_SOURCE_CACHE_SIZE = 1_024;
    private static final long COMBINED_TYPE_CACHE_SIZE = 20_000;
    static final String SOURCE_CACHE_PROPERTY = "anatomist.typeCache.sourceMaxEntries";
    static final String COMBINED_CACHE_PROPERTY = "anatomist.typeCache.combinedMaxEntries";

    private final int javaVersion;
    private final List<Path> classpathEntries;
    private final List<Path> sourcePaths;
    private final boolean includeRunningVmClasspath;
    private final Session session;
    private IndexTimings timings;

    public JavaParserFactory(int javaVersion,
                             List<Path> classpathEntries,
                             List<Path> sourcePaths,
                             boolean includeRunningVmClasspath) {
        this.javaVersion = javaVersion;
        this.classpathEntries = classpathEntries == null ? List.of() : List.copyOf(classpathEntries);
        this.sourcePaths = sourcePaths == null ? List.of() : List.copyOf(sourcePaths);
        this.includeRunningVmClasspath = includeRunningVmClasspath;
        this.session = null;
    }

    public JavaParserFactory(int javaVersion,
                             List<Path> classpathEntries,
                             List<Path> sourcePaths,
                             boolean includeRunningVmClasspath,
                             SessionCache sessions) {
        this.javaVersion = javaVersion;
        this.classpathEntries = classpathEntries == null ? List.of() : List.copyOf(classpathEntries);
        this.sourcePaths = sourcePaths == null ? List.of() : List.copyOf(sourcePaths);
        this.includeRunningVmClasspath = includeRunningVmClasspath;
        this.session = sessions == null ? null : sessions.acquire(
                javaVersion, this.classpathEntries, this.sourcePaths, includeRunningVmClasspath, this);
    }

    /** Build the combined TypeSolver matching the configured environment. */
    public CombinedTypeSolver newTypeSolver() {
        return newTypeSolver(fullSourceCacheSize(), null);
    }

    public void setTimings(IndexTimings timings) {
        this.timings = timings;
    }

    private CombinedTypeSolver newTypeSolver(int sourceCacheSize,
                                             List<AutoCloseable> closeables) {
        CombinedTypeSolver ts = new CombinedTypeSolver(
                exception -> false, List.<TypeSolver>of(), boundedCache(combinedTypeCacheSize()));
        ParserConfiguration sourceConfiguration = new ParserConfiguration()
                .setLanguageLevel(toLanguageLevel(javaVersion));
        // Source paths first — project types should resolve before JDK/classpath
        for (Path src : sourcePaths) {
            if (src != null && Files.isDirectory(src)) {
                ts.add(new JavaParserTypeSolver(src, sourceConfiguration, sourceCacheSize));
            }
        }
        if (includeRunningVmClasspath) {
            // ReflectionTypeSolver provides full JDK type information and works
            // correctly with JavaParser's symbol resolution. The EmbeddedJdkTypeSolver
            // (from jdkN-types.bin) is a native-image fallback only — its incomplete
            // type declarations break method resolution in CombinedTypeSolver.
            if (isNativeImage()) {
                TypeSolver jdkSolver = tryLoadEmbeddedJdkSolver();
                if (jdkSolver != null) {
                    ts.add(jdkSolver);
                } else {
                    ts.add(new ReflectionTypeSolver(/*jreOnly*/ true));
                }
            } else {
                ts.add(new ReflectionTypeSolver(/*jreOnly*/ true));
            }
        }
        if (hasUsableClasspathEntry()) {
            AsmTypeSolver solver = newAsmTypeSolver();
            ts.add(solver);
            if (closeables != null) closeables.add(solver);
        }
        return ts;
    }

    /** Look up a pre-generated JDK catalog under
     *  {@code META-INF/anatomist/jdkN-types.bin} matching the build target's
     *  Java version (falling back to the runtime's version). Returns {@code null}
     *  when no catalog ships with the current build — the caller then falls
     *  back to {@link ReflectionTypeSolver}. */
    private TypeSolver tryLoadEmbeddedJdkSolver() {
        int targetRelease = Math.max(javaVersion, 8);
        // Try the requested target first, then walk DOWN to the highest catalog
        // we shipped that's ≤ target. Catalogs are forward-compatible enough for
        // anatomist's erased-type needs.
        for (int v = targetRelease; v >= 8; v--) {
            String res = "/META-INF/anatomist/jdk" + v + "-types.bin";
            try (InputStream in = JavaParserFactory.class.getResourceAsStream(res)) {
                if (in == null) continue;
                JdkTypeCatalog cat = JdkTypeCatalog.readFrom(in);
                return new EmbeddedJdkTypeSolver(cat);
            } catch (IOException ignore) {
                // try next version
            }
        }
        return null;
    }

    public ParserConfiguration newConfiguration() {
        return new ParserConfiguration()
                .setLanguageLevel(toLanguageLevel(javaVersion))
                .setSymbolResolver(new JavaSymbolSolver(newTypeSolver()));
    }

    /**
     * Parse every {@code .java} file under each configured source root and
     * deliver the resulting {@link CompilationUnit}s to {@code consumer}.
     *
     * <p>The callback receives the source file's absolute path and the parsed
     * unit. Files that fail to parse are reported to stderr and skipped (soft
     * fail per file).</p>
     */
    public void parseAll(BiConsumer<Path, CompilationUnit> consumer) {
        Session ownedSession = session == null ? openSession(fullSourceCacheSize()) : null;
        ParserConfiguration cfg = session == null ? ownedSession.configuration : session.configuration;
        try {
            for (Path src : sourcePaths) {
                if (src == null || !Files.isDirectory(src)) continue;
                SourceRoot root = new SourceRoot(src, cfg);
                List<ParseResult<CompilationUnit>> results;
                try {
                    results = root.tryToParse();
                } catch (IOException e) {
                    AnatomistLog.warn("failed to walk source root " + src + ": " + e.getMessage());
                    continue;
                }
                for (ParseResult<CompilationUnit> result : results) {
                    if (!result.isSuccessful() || result.getResult().isEmpty()) {
                        result.getProblems().forEach(problem ->
                                AnatomistLog.debug("parse problem: " + problem.getMessage()));
                        continue;
                    }
                    CompilationUnit cu = result.getResult().get();
                    Path file = cu.getStorage().map(storage -> storage.getPath()).orElse(null);
                    consumer.accept(file, cu);
                }
            }
        } finally {
            if (ownedSession != null) ownedSession.close();
        }
    }

    /**
     * Parse the scanner's exact file inventory while preserving a failure for
     * every file that did not produce a usable compilation unit.
     */
    public ParseInventory parseInventory(List<Path> files,
                                         BiConsumer<Path, CompilationUnit> consumer) {
        List<Path> inventory = files == null ? List.of() : files.stream()
                .filter(java.util.Objects::nonNull)
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList();
        Map<Path, List<String>> failures = new LinkedHashMap<>();
        int parsed = 0;
        Session ownedSession = session == null ? openSession(fullSourceCacheSize()) : null;
        ParserConfiguration configuration =
                session == null ? ownedSession.configuration : session.configuration;
        JavaParser parser = new JavaParser(configuration);
        try {
            for (Path file : inventory) {
                try {
                    ParseResult<CompilationUnit> result = parser.parse(file);
                    if (!result.isSuccessful() || result.getResult().isEmpty()) {
                        List<String> messages = result.getProblems().stream()
                                .map(problem -> problem.getMessage())
                                .toList();
                        failures.put(file, messages.isEmpty()
                                ? List.of("parser produced no compilation unit")
                                : messages);
                        continue;
                    }
                    CompilationUnit unit = result.getResult().get();
                    consumer.accept(file, unit);
                    parsed++;
                } catch (IOException e) {
                    failures.put(file, List.of("failed to read source: " + e.getMessage()));
                } catch (RuntimeException e) {
                    failures.put(file, List.of("parser failed: " + e.getMessage()));
                }
            }
        } finally {
            if (ownedSession != null) ownedSession.close();
        }
        return new ParseInventory(inventory.size(), inventory.size(), parsed, failures);
    }

    /** Parse an explicit list of files (used by tests / non-SourceRoot flows). */
    public List<CompilationUnit> parseFiles(List<Path> files) {
        ParseFilesResult result = parseFilesDetailed(files);
        result.problems().forEach((file, problems) -> problems.forEach(problem ->
                System.err.println("WARN: parse problem in " + file + ": " + problem)));
        return result.compilationUnits();
    }

    /** Parse explicit files while preserving per-file diagnostics for incremental retry decisions. */
    public ParseFilesResult parseFilesDetailed(List<Path> files) {
        List<CompilationUnit> out = new ArrayList<>();
        Map<Path, List<String>> problems = new LinkedHashMap<>();
        JavaParser parser = new JavaParser(session == null ? newConfiguration() : session.configuration);
        for (Path f : files) {
            Path normalized = f.toAbsolutePath().normalize();
            try {
                ParseResult<CompilationUnit> pr = parser.parse(f);
                if (pr.isSuccessful() && pr.getResult().isPresent()) {
                    out.add(pr.getResult().get());
                } else {
                    List<String> messages = pr.getProblems().stream()
                            .map(problem -> problem.getMessage())
                            .toList();
                    problems.put(normalized, messages.isEmpty()
                            ? List.of("parser produced no compilation unit")
                            : messages);
                }
            } catch (IOException e) {
                problems.put(normalized, List.of("failed to read source: " + e.getMessage()));
            }
        }
        return new ParseFilesResult(List.copyOf(out), immutableProblems(problems));
    }

    private static Map<Path, List<String>> immutableProblems(Map<Path, List<String>> problems) {
        Map<Path, List<String>> copy = new LinkedHashMap<>();
        problems.forEach((path, messages) -> copy.put(path, List.copyOf(messages)));
        return java.util.Collections.unmodifiableMap(copy);
    }

    public record ParseFilesResult(List<CompilationUnit> compilationUnits,
                                   Map<Path, List<String>> problems) {}

    /** Invalidate source-backed caches before an incremental batch. */
    public void invalidate(Collection<Path> files, boolean directoryShapeMayHaveChanged) {
        invalidate(files, directoryShapeMayHaveChanged, null);
    }

    /** Invalidate changed source declarations without discarding unrelated classpath results. */
    public void invalidate(Collection<Path> files, boolean directoryShapeMayHaveChanged,
                           Collection<String> declaredTypeNames) {
        if (session != null) session.invalidate(files, directoryShapeMayHaveChanged, declaredTypeNames);
    }

    /** Watch-owned cache. A changed runtime signature atomically replaces the old session. */
    public static final class SessionCache implements AutoCloseable {
        private Session session;
        private String signature;

        private synchronized Session acquire(int javaVersion,
                                             List<Path> classpathEntries,
                                             List<Path> sourcePaths,
                                             boolean vmClasspath,
                                             JavaParserFactory factory) {
            String next = javaVersion + "|" + vmClasspath + "|" + sourcePaths + "|" + classpathEntries;
            if (session == null || !next.equals(signature)) {
                close();
                session = factory.openSession(watchSourceCacheSize());
                signature = next;
            }
            return session;
        }

        public synchronized void clear() {
            close();
        }

        @Override
        public synchronized void close() {
            if (session != null) session.close();
            session = null;
            signature = null;
        }
    }

    /** Persistent source/type caches used only by a single-threaded Watch session. */
    static final class Session implements AutoCloseable {
        private final ParserConfiguration configuration;
        private final Map<Path, ReloadableSourceSolver> sources;
        private final Cache<String, SymbolReference<ResolvedReferenceTypeDeclaration>> combinedTypes;
        private final List<AutoCloseable> closeables;

        private Session(ParserConfiguration configuration,
                        Map<Path, ReloadableSourceSolver> sources,
                        Cache<String, SymbolReference<ResolvedReferenceTypeDeclaration>> combinedTypes,
                        List<AutoCloseable> closeables) {
            this.configuration = configuration;
            this.sources = sources;
            this.combinedTypes = combinedTypes;
            this.closeables = closeables;
        }

        private void invalidate(Collection<Path> files, boolean directoryShapeMayHaveChanged,
                                Collection<String> declaredTypeNames) {
            if (declaredTypeNames == null || declaredTypeNames.isEmpty()) {
                combinedTypes.removeAll();
            } else {
                declaredTypeNames.forEach(combinedTypes::remove);
            }
            if (files == null) return;
            java.util.Set<Path> invalidatedRoots = new java.util.HashSet<>();
            for (Path file : files) {
                if (file == null) continue;
                Path normalized = file.toAbsolutePath().normalize();
                for (Map.Entry<Path, ReloadableSourceSolver> entry : sources.entrySet()) {
                    if (normalized.startsWith(entry.getKey())) {
                        invalidatedRoots.add(entry.getKey());
                        break;
                    }
                }
            }
            invalidatedRoots.forEach(root -> sources.get(root).reload());
        }

        @Override
        public void close() {
            for (AutoCloseable closeable : closeables) {
                try { closeable.close(); } catch (Exception ignore) {}
            }
            combinedTypes.removeAll();
            sources.clear();
        }
    }

    private static final class ReloadableSourceSolver implements TypeSolver {
        private final Path root;
        private final ParserConfiguration configuration;
        private TypeSolver parent;
        private JavaParserTypeSolver delegate;

        private ReloadableSourceSolver(Path root, ParserConfiguration configuration) {
            this.root = root;
            this.configuration = configuration;
            this.delegate = new JavaParserTypeSolver(root, configuration, watchSourceCacheSize());
        }

        private void reload() {
            JavaParserTypeSolver replacement = new JavaParserTypeSolver(
                    root, configuration, watchSourceCacheSize());
            if (parent != null) replacement.setParent(this);
            delegate = replacement;
        }

        @Override public TypeSolver getParent() { return parent; }

        @Override
        public void setParent(TypeSolver parent) {
            if (this.parent != null) throw new IllegalStateException("This TypeSolver already has a parent.");
            this.parent = parent;
            delegate.setParent(this);
        }

        @Override
        public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
            return delegate.tryToSolveType(name);
        }

        @Override
        public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveTypeInModule(
                String moduleName, String name) {
            return delegate.tryToSolveTypeInModule(moduleName, name);
        }
    }

    private Session openSession(int sourceCacheSize) {
        Cache<String, SymbolReference<ResolvedReferenceTypeDeclaration>> combinedTypes =
                boundedCache(combinedTypeCacheSize());
        CombinedTypeSolver ts = new CombinedTypeSolver(
                exception -> false, List.<TypeSolver>of(), combinedTypes);
        Map<Path, ReloadableSourceSolver> sourceSolvers = new LinkedHashMap<>();
        ParserConfiguration sourceConfiguration = new ParserConfiguration()
                .setLanguageLevel(toLanguageLevel(javaVersion));
        for (Path src : sourcePaths) {
            if (src == null || !Files.isDirectory(src)) continue;
            Path normalized = src.toAbsolutePath().normalize();
            ReloadableSourceSolver solver = sourceCacheSize == watchSourceCacheSize()
                    ? new ReloadableSourceSolver(normalized, sourceConfiguration)
                    : null;
            TypeSolver sourceSolver = solver != null
                    ? solver
                    : new JavaParserTypeSolver(normalized, sourceConfiguration, sourceCacheSize);
            ts.add(sourceSolver);
            if (solver != null) sourceSolvers.put(normalized, solver);
        }
        if (includeRunningVmClasspath) {
            if (isNativeImage()) {
                TypeSolver embedded = tryLoadEmbeddedJdkSolver();
                ts.add(embedded != null ? embedded : new ReflectionTypeSolver(true));
            } else {
                ts.add(new ReflectionTypeSolver(true));
            }
        }
        List<AutoCloseable> closeables = new ArrayList<>();
        if (hasUsableClasspathEntry()) {
            AsmTypeSolver solver = newAsmTypeSolver();
            ts.add(solver);
            closeables.add(solver);
        }
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(toLanguageLevel(javaVersion))
                .setSymbolResolver(new JavaSymbolSolver(ts));
        return new Session(configuration, sourceSolvers, combinedTypes, closeables);
    }

    private boolean hasUsableClasspathEntry() {
        return classpathEntries.stream().anyMatch(entry -> entry != null
                && (Files.isRegularFile(entry) || Files.isDirectory(entry)));
    }

    private AsmTypeSolver newAsmTypeSolver() {
        long started = timings == null ? 0L : System.nanoTime();
        ClasspathClassFileSource source = new ClasspathClassFileSource(classpathEntries, javaVersion);
        if (timings != null) timings.addNanos("classpath_index_build", System.nanoTime() - started);
        return new AsmTypeSolver(source,
                timings == null ? null : (phase, nanos) -> timings.addNanos(phase, nanos));
    }

    private static <K, V> Cache<K, V> boundedCache(long maximumSize) {
        return GuavaCache.create(CacheBuilder.newBuilder().maximumSize(maximumSize).build());
    }

    static int fullSourceCacheSize() {
        return positiveIntProperty(SOURCE_CACHE_PROPERTY, FULL_SOURCE_CACHE_SIZE);
    }

    static int watchSourceCacheSize() {
        return positiveIntProperty(SOURCE_CACHE_PROPERTY, WATCH_SOURCE_CACHE_SIZE);
    }

    static long combinedTypeCacheSize() {
        return positiveLongProperty(COMBINED_CACHE_PROPERTY, COMBINED_TYPE_CACHE_SIZE);
    }

    private static int positiveIntProperty(String key, int fallback) {
        long value = positiveLongProperty(key, fallback);
        return value > Integer.MAX_VALUE ? fallback : (int) value;
    }

    private static long positiveLongProperty(String key, long fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean isNativeImage() {
        return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
    }

    static LanguageLevel toLanguageLevel(int v) {
        return switch (v) {
            case 9 -> LanguageLevel.JAVA_9;
            case 10 -> LanguageLevel.JAVA_10;
            case 11 -> LanguageLevel.JAVA_11;
            case 12 -> LanguageLevel.JAVA_12;
            case 13 -> LanguageLevel.JAVA_13;
            case 14 -> LanguageLevel.JAVA_14;
            case 15 -> LanguageLevel.JAVA_15;
            case 16 -> LanguageLevel.JAVA_16;
            case 17 -> LanguageLevel.JAVA_17;
            case 18 -> LanguageLevel.JAVA_18;
            case 19 -> LanguageLevel.JAVA_19;
            case 20 -> LanguageLevel.JAVA_20;
            case 21 -> LanguageLevel.JAVA_21;
            default -> LanguageLevel.JAVA_8;
        };
    }
}
