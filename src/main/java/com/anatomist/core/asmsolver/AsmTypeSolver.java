package com.anatomist.core.asmsolver;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Native-image-friendly replacement for javassist-backed {@code JarTypeSolver}.
 *
 *  <p>Reads .class bytes from a {@link ClassFileSource} (jar on disk, or
 *  in-memory map for tests) via ASM — never invokes {@code ClassLoader}
 *  or javassist's {@code ClassPool}, so the closed-world assumption holds.
 *
 *  <p>Thread-safety: the per-FQN cache uses a synchronized map; safe under
 *  the single-threaded JavaParser parsing pipeline. */
public class AsmTypeSolver implements TypeSolver, AutoCloseable {

    private final ClassFileSource source;
    private final Map<String, AsmClassDeclaration> cache = new HashMap<>();
    private TypeSolver parent;

    public AsmTypeSolver(ClassFileSource source) {
        this.source = source;
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
        AsmClassDeclaration cached;
        synchronized (cache) {
            cached = cache.get(name);
        }
        if (cached != null) return SymbolReference.solved(cached);

        Optional<byte[]> bytes = source.find(name);
        if (bytes.isEmpty()) return SymbolReference.unsolved();

        AsmClassDeclaration decl;
        synchronized (cache) {
            AsmClassDeclaration existing = cache.get(name);
            if (existing != null) return SymbolReference.solved(existing);
            decl = new AsmClassDeclaration(name, bytes.get(), this);
            cache.put(name, decl);
        }
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
        if (source != null) source.close();
    }
}
