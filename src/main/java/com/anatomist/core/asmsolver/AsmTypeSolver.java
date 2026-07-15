package com.anatomist.core.asmsolver;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.anatomist.core.logging.AnatomistLog;

import java.util.Optional;
import java.util.function.BiConsumer;

/** Native-image-friendly replacement for javassist-backed {@code JarTypeSolver}.
 *
 *  <p>Reads .class bytes from a {@link ClassFileSource} (jar on disk, or
 *  in-memory map for tests) via ASM — never invokes {@code ClassLoader}
 *  or javassist's {@code ClassPool}, so the closed-world assumption holds.
 *
 *  <p>The per-FQN declaration cache is bounded. A large Maven classpath can
 *  expose hundreds of thousands of classes, while a single index run normally
 *  touches only a much smaller working set.</p> */
public class AsmTypeSolver implements TypeSolver, AutoCloseable {

    static final long DEFAULT_CACHE_MAX_BYTES = 256L * 1024 * 1024;
    static final String CACHE_MAX_BYTES_PROPERTY = "anatomist.typeCache.asmMaxBytes";

    private final ClassFileSource source;
    private final Cache<String, AsmClassDeclaration> cache;
    private final PackedTypeMetadataCache metadataCache;
    private final BiConsumer<String, Long> timingSink;
    private TypeSolver parent;

    public AsmTypeSolver(ClassFileSource source) {
        this(source, configuredMaxBytes(), null);
    }

    public AsmTypeSolver(ClassFileSource source, BiConsumer<String, Long> timingSink) {
        this(source, configuredMaxBytes(), timingSink);
    }

    AsmTypeSolver(ClassFileSource source, long maximumCacheBytes) {
        this(source, maximumCacheBytes, null);
    }

    private AsmTypeSolver(ClassFileSource source, long maximumCacheBytes,
                          BiConsumer<String, Long> timingSink) {
        if (maximumCacheBytes <= 0) {
            throw new IllegalArgumentException("maximumCacheBytes must be positive");
        }
        this.source = source;
        this.timingSink = timingSink;
        long loadStarted = System.nanoTime();
        this.metadataCache = source instanceof ClasspathClassFileSource classpath
                ? PackedTypeMetadataCache.open(classpath.metadataCacheFile(), classpath.fingerprint())
                : null;
        addTiming("type_cache_load", loadStarted);
        this.cache = CacheBuilder.newBuilder()
                .maximumWeight(maximumCacheBytes)
                .weigher((String ignored, AsmClassDeclaration value) -> value.estimatedCacheWeight())
                .recordStats()
                .build();
    }

    @Override
    public TypeSolver getParent() {
        return parent;
    }

    @Override
    public void setParent(TypeSolver parent) {
        if (parent == this) {
            throw new IllegalArgumentException("a TypeSolver cannot be its own parent");
        }
        this.parent = parent;
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
        AsmClassDeclaration cached = cache.getIfPresent(name);
        if (cached != null) return SymbolReference.solved(cached);

        AsmTypeMetadata metadata = metadataCache == null ? null : metadataCache.get(name);
        Optional<byte[]> bytes = metadata == null ? source.find(name) : Optional.empty();
        if (metadata == null && bytes.isEmpty()) return SymbolReference.unsolved();

        AsmClassDeclaration existing = cache.getIfPresent(name);
        if (existing != null) return SymbolReference.solved(existing);
        AsmClassDeclaration decl = metadata != null
                ? new AsmClassDeclaration(metadata, this)
                : new AsmClassDeclaration(name, bytes.get(), this);
        cache.put(name, decl);
        return SymbolReference.solved(decl);
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveTypeInModule(
            String moduleName, String typeName) {
        // anatomist treats jars as flat (no module-info-driven scoping).
        return tryToSolveType(typeName);
    }

    @Override
    public void close() {
        if (metadataCache != null) {
            for (AsmClassDeclaration declaration : cache.asMap().values()) {
                metadataCache.put(declaration.metadataIfParsed());
            }
            long writeStarted = System.nanoTime();
            metadataCache.write();
            addTiming("type_cache_write", writeStarted);
            AnatomistLog.debug("packed type cache: " + metadataCache.stats());
        }
        if (AnatomistLog.isDebugEnabled()) {
            var stats = cache.stats();
            AnatomistLog.debug("ASM type cache: entries=" + cache.size()
                    + " hits=" + stats.hitCount() + " misses=" + stats.missCount()
                    + " evictions=" + stats.evictionCount());
        }
        cache.invalidateAll();
        if (source != null) source.close();
    }

    long cachedTypeCount() {
        cache.cleanUp();
        return cache.size();
    }

    private static long configuredMaxBytes() {
        String value = System.getProperty(CACHE_MAX_BYTES_PROPERTY);
        if (value == null || value.isBlank()) return DEFAULT_CACHE_MAX_BYTES;
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : DEFAULT_CACHE_MAX_BYTES;
        } catch (NumberFormatException ignored) {
            return DEFAULT_CACHE_MAX_BYTES;
        }
    }

    private void addTiming(String phase, long started) {
        if (timingSink != null) timingSink.accept(phase, System.nanoTime() - started);
    }
}
