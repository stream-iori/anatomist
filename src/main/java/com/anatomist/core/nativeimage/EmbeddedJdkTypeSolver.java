package com.anatomist.core.nativeimage;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

/** Native-image-friendly replacement for {@code ReflectionTypeSolver}: serves
 *  JDK types from a pre-generated {@link JdkTypeCatalog} binary that ships
 *  inside the anatomist jar (or native binary). No {@code Class.forName} →
 *  closed-world compatible.
 *
 *  <p>The catalog is built at anatomist release time by
 *  {@link JdkTypeCatalogBuilder} and serialised to
 *  {@code META-INF/anatomist/jdkN-types.bin}. */
public class EmbeddedJdkTypeSolver implements TypeSolver {

    private final JdkTypeCatalog catalog;
    private final Map<String, EmbeddedJdkClassDeclaration> cache = new HashMap<>();
    private TypeSolver parent;

    public EmbeddedJdkTypeSolver(JdkTypeCatalog catalog) {
        this.catalog = catalog;
    }

    /** Load a catalog resource (e.g. {@code "/META-INF/anatomist/jdk21-types.bin"})
     *  from the classpath. */
    public static EmbeddedJdkTypeSolver loadFromResource(String resourcePath) {
        try (InputStream in = EmbeddedJdkTypeSolver.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("catalog resource not found: " + resourcePath);
            }
            return new EmbeddedJdkTypeSolver(JdkTypeCatalog.readFrom(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public JdkTypeCatalog catalog() { return catalog; }

    @Override
    public TypeSolver getParent() { return parent; }

    @Override
    public void setParent(TypeSolver parent) {
        if (parent == this) {
            throw new IllegalArgumentException("a TypeSolver cannot be its own parent");
        }
        this.parent = parent;
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
        EmbeddedJdkClassDeclaration cached;
        synchronized (cache) {
            cached = cache.get(name);
        }
        if (cached != null) return SymbolReference.solved(cached);

        JdkType t = catalog.find(name);
        if (t == null) return SymbolReference.unsolved();

        EmbeddedJdkClassDeclaration decl;
        synchronized (cache) {
            EmbeddedJdkClassDeclaration existing = cache.get(name);
            if (existing != null) return SymbolReference.solved(existing);
            decl = new EmbeddedJdkClassDeclaration(t, this);
            cache.put(name, decl);
        }
        return SymbolReference.solved(decl);
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveTypeInModule(
            String moduleName, String typeName) {
        return tryToSolveType(typeName);
    }
}
